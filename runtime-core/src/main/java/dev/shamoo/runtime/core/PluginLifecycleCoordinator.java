package dev.shamoo.runtime.core;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.function.Supplier;

/** Serializes plugin lifecycle operations and enforces dependency, drain, timeout, and cleanup rules. */
public final class PluginLifecycleCoordinator {
    private final PluginRuntimeFactory runtimeFactory;
    private final ResourceRegistry resources;
    private final Duration hookTimeout;
    private final Duration drainTimeout;
    private final QuarantinePolicy quarantinePolicy;
    private final Executor executor;
    private final PlatformCapabilities platformCapabilities;
    private final CrossPluginServiceRegistry serviceRegistry = new CrossPluginServiceRegistry();
    private final CrossPluginEventBus eventBus = new CrossPluginEventBus();
    private final Map<PluginId, ManagedPlugin> plugins = new ConcurrentHashMap<>();
    private final Map<PluginId, List<ResourceRegistry>> retiredLeaks = new ConcurrentHashMap<>();
    private final Object lifecycleLock = new Object();
    private CompletableFuture<Void> lifecycleTail = completed();
    private volatile DependencyResolution resolution = new DependencyResolution(
            List.of(), List.of(), Map.of(), Map.of(), List.of());

    public PluginLifecycleCoordinator(
            PluginRuntimeFactory runtimeFactory,
            ResourceRegistry resources,
            Duration hookTimeout,
            Duration drainTimeout,
            QuarantinePolicy quarantinePolicy,
            Executor executor) {
        this(runtimeFactory, resources, hookTimeout, drainTimeout, quarantinePolicy, executor,
                PlatformCapabilities.NONE);
    }

    public PluginLifecycleCoordinator(
            PluginRuntimeFactory runtimeFactory,
            ResourceRegistry resources,
            Duration hookTimeout,
            Duration drainTimeout,
            QuarantinePolicy quarantinePolicy,
            Executor executor,
            PlatformCapabilities platformCapabilities) {
        this.runtimeFactory = Objects.requireNonNull(runtimeFactory, "runtimeFactory");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.hookTimeout = positive(hookTimeout, "hookTimeout");
        this.drainTimeout = positive(drainTimeout, "drainTimeout");
        this.quarantinePolicy = Objects.requireNonNull(quarantinePolicy, "quarantinePolicy");
        this.executor = Objects.requireNonNull(executor, "executor");
        this.platformCapabilities = Objects.requireNonNull(platformCapabilities, "platformCapabilities");
    }

    public DependencyResolution install(Collection<InstalledPluginCandidate> candidates) {
        List<InstalledPluginCandidate> candidateSnapshot = List.copyOf(candidates);
        DependencyResolution next = new PluginDependencyGraph().resolve(candidateSnapshot);
        Map<PluginId, InstalledPluginCandidate> indexed = new LinkedHashMap<>();
        candidateSnapshot.forEach(candidate -> indexed.put(candidate.pluginId(), candidate));
        synchronized (lifecycleLock) {
            if (!lifecycleTail.isDone()) {
                throw new IllegalStateException("cannot install candidates while lifecycle operations are pending");
            }
            if (plugins.entrySet().stream().anyMatch(entry -> !indexed.containsKey(entry.getKey())
                    && entry.getValue().machine.state() != PluginLifecycleState.UNLOADED)) {
                throw new IllegalStateException("cannot remove a candidate before it is unloaded");
            }
            for (Map.Entry<PluginId, InstalledPluginCandidate> entry : indexed.entrySet()) {
                ManagedPlugin existing = plugins.get(entry.getKey());
                if (existing != null && existing.machine.state() != PluginLifecycleState.UNLOADED
                        && !existing.candidate.equals(entry.getValue())) {
                    throw new IllegalStateException("installed candidate cannot be replaced before transactional swap");
                }
            }
            Map<PluginId, ManagedPlugin> replacement = new LinkedHashMap<>();
            indexed.forEach((id, candidate) -> {
                ManagedPlugin existing = plugins.get(id);
                replacement.put(id, existing == null || (existing.machine.state() == PluginLifecycleState.UNLOADED
                                && !existing.candidate.equals(candidate))
                        ? new ManagedPlugin(candidate, resources) : existing);
            });
            replacement.values().forEach(plugin -> updateDependencyState(plugin, next));
            plugins.clear();
            plugins.putAll(replacement);
            resolution = next;
            return next;
        }
    }

    public CompletionStage<Void> load(PluginId pluginId, UUID correlationId) {
        return serialized(() -> doLoad(plugin(pluginId), correlationId));
    }

    public CompletionStage<Void> enable(PluginId pluginId, UUID correlationId) {
        return serialized(() -> doEnable(plugin(pluginId), correlationId));
    }

    public CompletionStage<Void> ready(PluginId pluginId, UUID correlationId) {
        return serialized(() -> doReady(plugin(pluginId), correlationId));
    }

    public CompletionStage<Void> disable(PluginId pluginId, UUID correlationId) {
        return serialized(() -> doDisableWithDependents(pluginId, correlationId));
    }

    public CompletionStage<Void> unload(PluginId pluginId, UUID correlationId) {
        return serialized(() -> doUnload(plugin(pluginId), correlationId));
    }

    /** Stages and transactionally replaces one installed plugin candidate. */
    public CompletionStage<Void> stageAndReplace(
            Path source, Path stagingRoot, PluginStager stager, UUID correlationId) {
        Objects.requireNonNull(stager, "stager");
        try {
            return replace(stager.stage(source, stagingRoot), correlationId);
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    /** Prepares a candidate, drains the old generation, then atomically switches invocation admission. */
    public CompletionStage<Void> replace(InstalledPluginCandidate candidate, UUID correlationId) {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(correlationId, "correlationId");
        return serialized(() -> doReplace(candidate, correlationId));
    }

    public CompletionStage<Void> enableAll(UUID correlationId) {
        return serialized(() -> {
            CompletionStage<Void> sequence = completed();
            for (PluginId id : resolution.enableOrder()) {
                ManagedPlugin managed = plugin(id);
                sequence = sequence.thenCompose(ignored -> doLoad(managed, correlationId))
                        .thenCompose(ignored -> doEnable(managed, correlationId))
                        .thenCompose(ignored -> doReady(managed, correlationId));
            }
            return sequence;
        });
    }

    public CompletionStage<Void> disableAll(UUID correlationId) {
        return serialized(() -> {
            CompletionStage<Void> sequence = completed();
            for (PluginId id : resolution.disableOrder()) {
                ManagedPlugin managed = plugin(id);
                if (isActive(managed.machine.state())) {
                    sequence = sequence.thenCompose(ignored -> doDisable(managed, correlationId));
                }
            }
            return sequence;
        });
    }

    public InvocationAdmission.Lease admitInvocation(PluginId pluginId) {
        return plugin(pluginId).invocations.admit();
    }

    public PluginIntrospectionSnapshot snapshot(PluginId pluginId) {
        ManagedPlugin plugin = plugin(pluginId);
        return new PluginIntrospectionSnapshot(
                pluginId,
                plugin.machine.state(),
                plugin.machine.history(),
                resolution.dependencies().getOrDefault(pluginId, Set.of()),
                resolution.blocked().getOrDefault(pluginId, List.of()),
                resourceSnapshot(plugin),
                plugin.invocations.snapshot(),
                plugin.metrics(),
                Instant.now());
    }

    public List<PluginIntrospectionSnapshot> snapshots() {
        return plugins.keySet().stream().sorted(java.util.Comparator.comparing(PluginId::value))
                .map(this::snapshot).toList();
    }

    private CompletionStage<Void> doLoad(ManagedPlugin plugin, UUID correlationId) {
        if (plugin.machine.state() == PluginLifecycleState.QUARANTINED) {
            return quarantined(plugin, correlationId);
        }
        if (plugin.machine.state() == PluginLifecycleState.LOADED) {
            return completed();
        }
        plugin.transition(PluginLifecycleState.LOADING, correlationId, "load requested");
        plugin.operations.incrementAndGet();
        PluginRuntimeContext context = new PluginRuntimeContext(
                plugin.candidate, plugin.resources, plugin.invocations, platformCapabilities,
                plugin.services, plugin.events, plugin.generationId);
        CompletionStage<PluginRuntime> creation;
        try {
            creation = plugin.runtime == null
                    ? timed(runtimeFactory.create(context), plugin, PluginLifecycleState.LOADING,
                            correlationId, hookTimeout, runtime -> cleanupLateRuntime(plugin, runtime))
                    : CompletableFuture.completedFuture(plugin.runtime);
        } catch (RuntimeException exception) {
            creation = CompletableFuture.failedFuture(exception);
        }
        return creation
                .thenCompose(runtime -> {
                    plugin.runtime = runtime;
                    return hook(runtime::load, plugin, PluginLifecycleState.LOADING,
                            PluginLifecycleState.LOADED, PluginLifecycleState.LOAD_FAILED, correlationId);
                }).exceptionallyCompose(failure -> {
                    if (plugin.machine.state() == PluginLifecycleState.LOADING) {
                        return fail(plugin, PluginLifecycleState.LOAD_FAILED, correlationId, failure);
                    }
                    return CompletableFuture.failedFuture(unwrap(failure));
                });
    }

    private CompletionStage<Void> doEnable(ManagedPlugin plugin, UUID correlationId) {
        return doEnable(plugin, correlationId, resolution);
    }

    private CompletionStage<Void> doEnable(
            ManagedPlugin plugin, UUID correlationId, DependencyResolution dependencyResolution) {
        if (plugin.machine.state() == PluginLifecycleState.QUARANTINED) {
            return quarantined(plugin, correlationId);
        }
        if (plugin.machine.state() == PluginLifecycleState.ENABLED
                || plugin.machine.state() == PluginLifecycleState.READY) {
            return completed();
        }
        for (PluginId dependency : dependencyResolution.dependencies().getOrDefault(
                plugin.candidate.pluginId(), Set.of())) {
            PluginLifecycleState dependencyState = plugin(dependency).machine.state();
            if (dependencyState != PluginLifecycleState.ENABLED
                    && dependencyState != PluginLifecycleState.READY) {
                return CompletableFuture.failedFuture(new PluginDependencyError(
                        plugin.candidate.pluginId(), PluginLifecycleState.ENABLING, correlationId,
                        "dependency_not_enabled", "dependency " + dependency + " is " + dependencyState));
            }
        }
        plugin.transition(PluginLifecycleState.ENABLING, correlationId, "enable requested");
        return hook(plugin.runtime::enable, plugin, PluginLifecycleState.ENABLING,
                PluginLifecycleState.ENABLED, PluginLifecycleState.ENABLE_FAILED, correlationId);
    }

    private CompletionStage<Void> doReady(ManagedPlugin plugin, UUID correlationId) {
        return doReady(plugin, correlationId, true);
    }

    private CompletionStage<Void> doReady(ManagedPlugin plugin, UUID correlationId, boolean openInvocations) {
        if (plugin.machine.state() == PluginLifecycleState.QUARANTINED) {
            return quarantined(plugin, correlationId);
        }
        if (plugin.machine.state() == PluginLifecycleState.READY) {
            return completed();
        }
        plugin.transition(PluginLifecycleState.READYING, correlationId, "ready requested");
        return hook(plugin.runtime::ready, plugin, PluginLifecycleState.READYING,
                PluginLifecycleState.READY, PluginLifecycleState.READY_FAILED, correlationId)
                .thenRun(() -> {
                    if (openInvocations) {
                        plugin.invocations.open();
                        serviceRegistry.activate(plugin.generationId);
                        eventBus.activate(plugin.generationId);
                    }
                });
    }

    private CompletionStage<Void> doDisable(ManagedPlugin plugin, UUID correlationId) {
        if (plugin.machine.state() == PluginLifecycleState.QUARANTINED) {
            return quarantined(plugin, correlationId);
        }
        if (plugin.machine.state() == PluginLifecycleState.DISABLED) {
            return completed();
        }
        plugin.transition(PluginLifecycleState.DRAINING, correlationId, "disable drain requested");
        plugin.invocations.stop();
        serviceRegistry.deactivate(plugin.generationId);
        eventBus.deactivate(plugin.generationId);
        return hookOnly(plugin.runtime::drain, plugin, PluginLifecycleState.DRAINING, correlationId)
                .thenCompose(ignored -> timed(plugin.invocations.awaitDrained(drainTimeout), plugin,
                        PluginLifecycleState.DRAINING, correlationId, drainTimeout).thenApply(value -> (Void) null))
                .thenCompose(ignored -> disableHook(plugin, correlationId))
                .exceptionallyCompose(failure -> {
                    if (plugin.machine.state() == PluginLifecycleState.DRAINING) {
                        return fail(plugin, PluginLifecycleState.DRAIN_FAILED, correlationId, failure);
                    }
                    return CompletableFuture.failedFuture(unwrap(failure));
                });
    }

    private CompletionStage<Void> doDisableWithDependents(PluginId pluginId, UUID correlationId) {
        Objects.requireNonNull(correlationId, "correlationId");
        CompletionStage<Void> sequence = completed();
        for (PluginId dependent : resolution.disableOrder()) {
            if (!dependent.equals(pluginId) && dependsOn(dependent, pluginId, new java.util.HashSet<>())) {
                ManagedPlugin managed = plugin(dependent);
                if (isActive(managed.machine.state())) {
                    sequence = sequence.thenCompose(ignored -> doDisable(managed, correlationId));
                }
            }
        }
        return sequence.thenCompose(ignored -> doDisable(plugin(pluginId), correlationId));
    }

    private CompletionStage<Void> disableHook(ManagedPlugin plugin, UUID correlationId) {
        plugin.transition(PluginLifecycleState.DISABLING, correlationId, "drain complete");
        return hook(plugin.runtime::disable, plugin, PluginLifecycleState.DISABLING,
                PluginLifecycleState.DISABLED, PluginLifecycleState.DISABLE_FAILED, correlationId);
    }

    @SuppressWarnings("PMD.NullAssignment")
    private CompletionStage<Void> doUnload(ManagedPlugin plugin, UUID correlationId) {
        if (plugin.machine.state() == PluginLifecycleState.UNLOADED) {
            return completed();
        }
        plugin.transition(PluginLifecycleState.UNLOADING, correlationId, "unload requested");
        plugin.invocations.stop();
        CompletionStage<Void> drained = timed(plugin.invocations.awaitDrained(drainTimeout), plugin,
                PluginLifecycleState.UNLOADING, correlationId, drainTimeout);
        return drained.thenCompose(ignored -> plugin.runtime == null || plugin.unloadHookComplete
                ? completed() : hookOnly(plugin.runtime::unload, plugin,
                        PluginLifecycleState.UNLOADING, correlationId)).thenCompose(ignored -> {
            plugin.unloadHookComplete = true;
            ResourceCleanupReport report = plugin.resources.cleanup(plugin.candidate.pluginId());
            List<Exception> retiredErrors = retryRetiredResources(plugin.candidate.pluginId());
            serviceRegistry.retire(plugin.generationId);
            eventBus.deactivate(plugin.generationId);
            if (!report.clean() || !retiredErrors.isEmpty()) {
                plugin.failedHooks.incrementAndGet();
                plugin.transition(PluginLifecycleState.UNLOAD_FAILED, correlationId, "resource cleanup failed");
                if (quarantinePolicy.quarantineResourceLeaks()) {
                    quarantine(plugin, correlationId, "resource cleanup leaked or failed");
                }
                return CompletableFuture.failedFuture(new ResourceCleanupError(
                        plugin.candidate.pluginId(), correlationId,
                        aggregate(java.util.stream.Stream.concat(report.errors().stream(), retiredErrors.stream())
                                .toList())));
            }
            plugin.runtime = null;
            plugin.successfulHooks.incrementAndGet();
            plugin.transition(PluginLifecycleState.UNLOADED, correlationId, "unload complete");
            return completed();
        }).exceptionallyCompose(failure -> {
            if (plugin.machine.state() == PluginLifecycleState.UNLOADING) {
                return fail(plugin, PluginLifecycleState.UNLOAD_FAILED, correlationId, failure);
            }
            return CompletableFuture.failedFuture(unwrap(failure));
        });
    }

    private CompletionStage<Void> hook(
            Supplier<CompletionStage<Void>> invocation,
            ManagedPlugin plugin,
            PluginLifecycleState phase,
            PluginLifecycleState success,
            PluginLifecycleState failed,
            UUID correlationId) {
        return hookOnly(invocation, plugin, phase, correlationId).thenRun(() -> {
            plugin.failures.set(0);
            plugin.successfulHooks.incrementAndGet();
            plugin.transition(success, correlationId, phase + " hook complete");
        }).exceptionallyCompose(failure -> fail(plugin, failed, correlationId, failure));
    }

    private CompletionStage<Void> hookOnly(
            Supplier<CompletionStage<Void>> invocation,
            ManagedPlugin plugin,
            PluginLifecycleState phase,
            UUID correlationId) {
        plugin.operations.incrementAndGet();
        try {
            return timed(invocation.get(), plugin, phase, correlationId).thenApply(ignored -> null);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private <T> CompletionStage<T> timed(
            CompletionStage<T> stage,
            ManagedPlugin plugin,
            PluginLifecycleState phase,
            UUID correlationId) {
        return timed(stage, plugin, phase, correlationId, hookTimeout);
    }

    private <T> CompletionStage<T> timed(
            CompletionStage<T> stage,
            ManagedPlugin plugin,
            PluginLifecycleState phase,
            UUID correlationId,
            Duration timeout) {
        return timed(stage, plugin, phase, correlationId, timeout, ignored -> { });
    }

    private <T> CompletionStage<T> timed(
            CompletionStage<T> stage,
            ManagedPlugin plugin,
            PluginLifecycleState phase,
            UUID correlationId,
            Duration timeout,
            Consumer<T> lateSuccess) {
        Objects.requireNonNull(stage, "lifecycle hook returned null");
        Objects.requireNonNull(lateSuccess, "lateSuccess");
        CompletableFuture<T> source = stage.toCompletableFuture();
        CompletableFuture<T> result = new CompletableFuture<>();
        long fence = plugin.fence.incrementAndGet();
        source.whenComplete((value, failure) -> {
            if (failure == null) {
                if (plugin.fence.get() != fence || !result.complete(value)) {
                    lateSuccess.accept(value);
                }
            } else if (plugin.fence.get() == fence) {
                Throwable cause = unwrap(failure);
                if (cause instanceof TimeoutException) {
                    LifecycleTimeoutError timeoutError = new LifecycleTimeoutError(
                            plugin.candidate.pluginId(), phase, correlationId, timeout, cause);
                    if (result.completeExceptionally(timeoutError)) {
                        plugin.timedOutHooks.incrementAndGet();
                        plugin.fence.compareAndSet(fence, fence + 1);
                        source.cancel(true);
                    }
                } else {
                    result.completeExceptionally(cause);
                }
            }
        });
        CompletableFuture.delayedExecutor(timeout.toMillis(), TimeUnit.MILLISECONDS).execute(() -> {
            LifecycleTimeoutError timeoutError = new LifecycleTimeoutError(
                    plugin.candidate.pluginId(), phase, correlationId, timeout, new TimeoutException());
            if (plugin.fence.compareAndSet(fence, fence + 1) && result.completeExceptionally(timeoutError)) {
                plugin.timedOutHooks.incrementAndGet();
                source.cancel(true);
            }
        });
        return result;
    }

    private void cleanupLateRuntime(ManagedPlugin plugin, PluginRuntime runtime) {
        try {
            CompletionStage<Void> cleanup = Objects.requireNonNull(runtime.unload(), "unload hook result");
            cleanup.whenComplete((ignored, failure) -> plugin.resources.cleanup(plugin.candidate.pluginId()));
        } catch (RuntimeException exception) {
            plugin.resources.cleanup(plugin.candidate.pluginId());
        }
    }

    private CompletionStage<Void> doReplace(InstalledPluginCandidate candidate, UUID correlationId) {
        ManagedPlugin active = plugin(candidate.pluginId());
        if (active.machine.state() != PluginLifecycleState.READY) {
            return CompletableFuture.failedFuture(new IllegalStateException(
                    "plugin must be ready before replacement: " + candidate.pluginId()));
        }
        if (active.candidate.equals(candidate)) {
            return completed();
        }
        DependencyResolution next;
        try {
            next = replacementResolution(candidate);
            validateReplacement(next, candidate.pluginId());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        ManagedPlugin staged = new ManagedPlugin(candidate, new ResourceRegistry());
        java.util.concurrent.atomic.AtomicBoolean swapped = new java.util.concurrent.atomic.AtomicBoolean();
        Set<PluginId> reloadDependents = serviceRegistry.reloadDependents(candidate.pluginId());
        return doLoad(staged, correlationId)
                .thenCompose(ignored -> doEnable(staged, correlationId, next))
                .thenCompose(ignored -> doReady(staged, correlationId, false))
                .thenCompose(ignored -> migrateState(active, staged, correlationId))
                .thenCompose(ignored -> disableReloadDependents(reloadDependents, correlationId))
                .thenCompose(ignored -> doDisable(active, correlationId))
                .thenRun(() -> {
                    synchronized (lifecycleLock) {
                        staged.invocations.open();
                        serviceRegistry.activate(staged.generationId);
                        eventBus.activate(staged.generationId);
                        resolution = next;
                        plugins.put(candidate.pluginId(), staged);
                        swapped.set(true);
                    }
                })
                .thenCompose(ignored -> cleanupAfterSwap(active, reloadDependents, correlationId))
                .whenComplete((ignored, failure) -> {
                    if (swapped.get() && failure != null) {
                        if (!active.resources.snapshot(candidate.pluginId()).isEmpty()) {
                            retiredLeaks.computeIfAbsent(candidate.pluginId(), pluginId ->
                                    new java.util.concurrent.CopyOnWriteArrayList<>()).add(active.resources);
                        }
                    }
                })
                .exceptionallyCompose(failure -> swapped.get()
                        ? CompletableFuture.failedFuture(unwrap(failure))
                        : enableReloadDependents(reloadDependents, correlationId)
                                .handle((ignored, recoveryFailure) -> {
                                    Throwable original = unwrap(failure);
                                    if (recoveryFailure != null) {
                                        original.addSuppressed(unwrap(recoveryFailure));
                                    }
                                    return original;
                                }).thenCompose(original -> discardCandidate(staged, correlationId, original)));
    }

    private CompletionStage<Void> cleanupAfterSwap(
            ManagedPlugin retired, Set<PluginId> reloadDependents, UUID correlationId) {
        return enableReloadDependents(reloadDependents, correlationId)
                .handle((ignored, dependentFailure) -> dependentFailure)
                .thenCompose(dependentFailure -> doUnload(retired, correlationId)
                        .handle((ignored, cleanupFailure) -> {
                            if (dependentFailure == null && cleanupFailure == null) {
                                return null;
                            }
                            Throwable failure = dependentFailure == null
                                    ? unwrap(cleanupFailure) : unwrap(dependentFailure);
                            if (cleanupFailure != null && dependentFailure != null) {
                                failure.addSuppressed(unwrap(cleanupFailure));
                            }
                            throw new CompletionException(failure);
                        }));
    }

    private DependencyResolution replacementResolution(InstalledPluginCandidate candidate) {
        List<InstalledPluginCandidate> candidates = plugins.values().stream()
                .map(plugin -> plugin.candidate.pluginId().equals(candidate.pluginId())
                        ? candidate : plugin.candidate)
                .toList();
        return new PluginDependencyGraph().resolve(candidates);
    }

    private void validateReplacement(DependencyResolution next, PluginId replacementId) {
        if (next.blocked().containsKey(replacementId)) {
            throw new IllegalArgumentException("replacement candidate is dependency-blocked: "
                    + next.blocked().get(replacementId));
        }
        for (Map.Entry<PluginId, ManagedPlugin> entry : plugins.entrySet()) {
            if (isActive(entry.getValue().machine.state()) && next.blocked().containsKey(entry.getKey())) {
                throw new IllegalArgumentException("replacement would block active dependent " + entry.getKey());
            }
        }
    }

    private CompletionStage<Void> migrateState(
            ManagedPlugin active, ManagedPlugin staged, UUID correlationId) {
        if (!staged.candidate.descriptor().reload().preserveState()
                || !(active.runtime instanceof HotStatePluginRuntime source)
                || !(staged.runtime instanceof HotStatePluginRuntime target)) {
            return completed();
        }
        active.operations.incrementAndGet();
        staged.operations.incrementAndGet();
        return timed(source.exportHotState(), active, PluginLifecycleState.READY, correlationId)
                .thenCompose(state -> {
                    byte[] snapshot = Objects.requireNonNull(state, "exported hot state").clone();
                    return timed(target.importHotState(snapshot), staged,
                            PluginLifecycleState.READY, correlationId);
                });
    }

    private CompletionStage<Void> disableReloadDependents(Set<PluginId> dependents, UUID correlationId) {
        CompletionStage<Void> sequence = completed();
        for (PluginId id : resolution.disableOrder()) {
            if (dependents.contains(id) && isActive(plugin(id).machine.state())) {
                sequence = sequence.thenCompose(ignored -> doDisable(plugin(id), correlationId));
            }
        }
        return sequence;
    }

    private CompletionStage<Void> enableReloadDependents(Set<PluginId> dependents, UUID correlationId) {
        CompletionStage<Void> sequence = completed();
        for (PluginId id : resolution.enableOrder()) {
            if (dependents.contains(id)) {
                ManagedPlugin dependent = plugin(id);
                sequence = sequence.thenCompose(ignored -> doEnable(dependent, correlationId))
                        .thenCompose(ignored -> doReady(dependent, correlationId));
            }
        }
        return sequence;
    }

    private CompletionStage<Void> discardCandidate(
            ManagedPlugin staged, UUID correlationId, Throwable original) {
        staged.invocations.stop();
        CompletionStage<Void> unload;
        try {
            unload = staged.runtime == null ? completed()
                    : timed(Objects.requireNonNull(staged.runtime.unload(), "unload hook result"),
                            staged, PluginLifecycleState.UNLOADING, correlationId);
        } catch (RuntimeException exception) {
            unload = CompletableFuture.failedFuture(exception);
        }
        return unload.handle((ignored, cleanupFailure) -> {
            ResourceCleanupReport report = staged.resources.cleanup(staged.candidate.pluginId());
            serviceRegistry.retire(staged.generationId);
            eventBus.deactivate(staged.generationId);
            if (cleanupFailure != null) {
                original.addSuppressed(unwrap(cleanupFailure));
            }
            if (!report.clean()) {
                original.addSuppressed(aggregate(report.errors()));
            }
            throw new CompletionException(original);
        });
    }

    private List<ResourceRegistration> resourceSnapshot(ManagedPlugin plugin) {
        List<ResourceRegistration> snapshot = new java.util.ArrayList<>(
                plugin.resources.snapshot(plugin.candidate.pluginId()));
        retiredLeaks.getOrDefault(plugin.candidate.pluginId(), List.of()).forEach(registry ->
                snapshot.addAll(registry.snapshot(plugin.candidate.pluginId())));
        return List.copyOf(snapshot);
    }

    private List<Exception> retryRetiredResources(PluginId pluginId) {
        List<ResourceRegistry> registries = retiredLeaks.getOrDefault(pluginId, List.of());
        List<Exception> errors = new java.util.ArrayList<>();
        if (registries.isEmpty()) {
            return List.of();
        }
        registries.removeIf(registry -> {
            ResourceCleanupReport report = registry.cleanup(pluginId);
            errors.addAll(report.errors());
            return report.clean();
        });
        if (registries.isEmpty()) {
            retiredLeaks.remove(pluginId, registries);
        }
        return List.copyOf(errors);
    }

    private CompletionStage<Void> fail(
            ManagedPlugin plugin,
            PluginLifecycleState failed,
            UUID correlationId,
            Throwable failure) {
        Throwable cause = unwrap(failure);
        if (plugin.machine.state() != failed) {
            plugin.transition(failed, correlationId, "hook failed: " + cause.getClass().getSimpleName());
        }
        plugin.failedHooks.incrementAndGet();
        int failures = plugin.failures.incrementAndGet();
        LifecycleError error = cause instanceof LifecycleError lifecycle
                ? lifecycle : phaseError(plugin.candidate.pluginId(), failed, correlationId, cause);
        if (failures >= quarantinePolicy.failuresBeforeQuarantine()) {
            quarantine(plugin, correlationId, "failure threshold reached");
        }
        return CompletableFuture.failedFuture(error);
    }

    private void quarantine(ManagedPlugin plugin, UUID correlationId, String reason) {
        if (plugin.machine.state() != PluginLifecycleState.QUARANTINED) {
            plugin.transition(PluginLifecycleState.QUARANTINED, correlationId, reason);
            plugin.quarantines.incrementAndGet();
            plugin.invocations.stop();
        }
    }

    private static CompletableFuture<Void> quarantined(ManagedPlugin plugin, UUID correlationId) {
        return CompletableFuture.failedFuture(
                new PluginQuarantinedError(plugin.candidate.pluginId(), correlationId));
    }

    private static LifecycleError phaseError(
            PluginId pluginId,
            PluginLifecycleState failed,
            UUID correlationId,
            Throwable cause) {
        return switch (failed) {
            case LOAD_FAILED -> new PluginLoadError(pluginId, correlationId, cause);
            case ENABLE_FAILED -> new PluginEnableError(pluginId, correlationId, cause);
            case READY_FAILED -> new PluginReadyError(pluginId, correlationId, cause);
            case DRAIN_FAILED -> new PluginDrainError(pluginId, correlationId, cause);
            case DISABLE_FAILED -> new PluginDisableError(pluginId, correlationId, cause);
            case UNLOAD_FAILED -> new PluginUnloadError(pluginId, correlationId, cause);
            default -> new PluginLifecycleError(pluginId, failed, correlationId,
                    failed.name().toLowerCase(java.util.Locale.ROOT), cause);
        };
    }

    private CompletionStage<Void> serialized(Supplier<CompletionStage<Void>> operation) {
        synchronized (lifecycleLock) {
            CompletableFuture<Void> result = lifecycleTail.handleAsync((ignored, previousFailure) -> null, executor)
                    .thenCompose(ignored -> operation.get()).toCompletableFuture();
            lifecycleTail = result.handle((ignored, failure) -> null);
            return result;
        }
    }

    private static void updateDependencyState(ManagedPlugin plugin, DependencyResolution next) {
        boolean blocked = next.blocked().containsKey(plugin.candidate.pluginId());
        if (blocked && plugin.machine.state() == PluginLifecycleState.DISCOVERED) {
            plugin.machine.transition(PluginLifecycleState.BLOCKED, UUID.randomUUID(), "dependency resolution");
        } else if (!blocked && plugin.machine.state() == PluginLifecycleState.BLOCKED) {
            plugin.machine.transition(PluginLifecycleState.DISCOVERED, UUID.randomUUID(),
                    "dependency compatibility restored");
        }
    }

    private ManagedPlugin plugin(PluginId pluginId) {
        ManagedPlugin plugin = plugins.get(Objects.requireNonNull(pluginId, "pluginId"));
        if (plugin == null) {
            throw new IllegalArgumentException("plugin is not installed: " + pluginId);
        }
        return plugin;
    }

    private boolean dependsOn(PluginId owner, PluginId target, Set<PluginId> visited) {
        if (!visited.add(owner)) {
            return false;
        }
        Set<PluginId> dependencies = resolution.dependencies().getOrDefault(owner, Set.of());
        return dependencies.contains(target)
                || dependencies.stream().anyMatch(dependency -> dependsOn(dependency, target, visited));
    }

    private static boolean isActive(PluginLifecycleState state) {
        return state == PluginLifecycleState.ENABLED
                || state == PluginLifecycleState.READY
                || state == PluginLifecycleState.READY_FAILED
                || state == PluginLifecycleState.DRAIN_FAILED;
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.isNegative() || duration.isZero()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }

    private static CompletableFuture<Void> completed() {
        return CompletableFuture.completedFuture(null);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof java.util.concurrent.ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static Exception aggregate(List<Exception> errors) {
        Exception aggregate = new Exception("resource cleanup failed");
        errors.forEach(aggregate::addSuppressed);
        return aggregate;
    }

    private final class ManagedPlugin {
        private final InstalledPluginCandidate candidate;
        private final PluginLifecycleMachine machine;
        private final InvocationAdmission invocations;
        private final ResourceRegistry resources;
        private final UUID generationId = UUID.randomUUID();
        private final PluginServices services;
        private final PluginEvents events;
        private volatile PluginRuntime runtime;
        private volatile boolean unloadHookComplete;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong operations = new AtomicLong();
        private final AtomicLong successfulHooks = new AtomicLong();
        private final AtomicLong failedHooks = new AtomicLong();
        private final AtomicLong timedOutHooks = new AtomicLong();
        private final AtomicLong quarantines = new AtomicLong();
        private final AtomicLong fence = new AtomicLong();

        private ManagedPlugin(InstalledPluginCandidate candidate, ResourceRegistry resources) {
            this.candidate = candidate;
            this.resources = resources;
            machine = new PluginLifecycleMachine(candidate.pluginId());
            invocations = new InvocationAdmission(candidate.pluginId());
            services = serviceRegistry.scoped(generationId, candidate.pluginId(), invocations, resources);
            events = eventBus.scoped(generationId, candidate.pluginId(), invocations, resources);
        }

        private void transition(PluginLifecycleState state, UUID correlationId, String reason) {
            machine.transition(state, correlationId, reason);
        }

        private LifecycleMetricsSnapshot metrics() {
            return new LifecycleMetricsSnapshot(
                    operations.get(), successfulHooks.get(), failedHooks.get(),
                    timedOutHooks.get(), quarantines.get());
        }
    }
}
