package dev.shamoo.runtime.core;

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
    private final Map<PluginId, ManagedPlugin> plugins = new ConcurrentHashMap<>();
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
                if (existing != null && !existing.candidate.equals(entry.getValue())) {
                    throw new IllegalStateException("installed candidate cannot be replaced before transactional swap");
                }
            }
            Map<PluginId, ManagedPlugin> replacement = new LinkedHashMap<>();
            indexed.forEach((id, candidate) -> {
                ManagedPlugin existing = plugins.get(id);
                replacement.put(id, existing == null ? new ManagedPlugin(candidate) : existing);
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
                resources.snapshot(pluginId),
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
                plugin.candidate, resources, plugin.invocations, platformCapabilities);
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
        if (plugin.machine.state() == PluginLifecycleState.QUARANTINED) {
            return quarantined(plugin, correlationId);
        }
        if (plugin.machine.state() == PluginLifecycleState.ENABLED
                || plugin.machine.state() == PluginLifecycleState.READY) {
            return completed();
        }
        for (PluginId dependency : resolution.dependencies().getOrDefault(
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
        if (plugin.machine.state() == PluginLifecycleState.QUARANTINED) {
            return quarantined(plugin, correlationId);
        }
        if (plugin.machine.state() == PluginLifecycleState.READY) {
            return completed();
        }
        plugin.transition(PluginLifecycleState.READYING, correlationId, "ready requested");
        return hook(plugin.runtime::ready, plugin, PluginLifecycleState.READYING,
                PluginLifecycleState.READY, PluginLifecycleState.READY_FAILED, correlationId)
                .thenRun(plugin.invocations::open);
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
            ResourceCleanupReport report = resources.cleanup(plugin.candidate.pluginId());
            if (!report.clean()) {
                plugin.failedHooks.incrementAndGet();
                plugin.transition(PluginLifecycleState.UNLOAD_FAILED, correlationId, "resource cleanup failed");
                if (quarantinePolicy.quarantineResourceLeaks()) {
                    quarantine(plugin, correlationId, "resource cleanup leaked or failed");
                }
                return CompletableFuture.failedFuture(new ResourceCleanupError(
                        plugin.candidate.pluginId(), correlationId, aggregate(report.errors())));
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
            cleanup.whenComplete((ignored, failure) -> resources.cleanup(plugin.candidate.pluginId()));
        } catch (RuntimeException exception) {
            resources.cleanup(plugin.candidate.pluginId());
        }
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
        private volatile PluginRuntime runtime;
        private volatile boolean unloadHookComplete;
        private final AtomicInteger failures = new AtomicInteger();
        private final AtomicLong operations = new AtomicLong();
        private final AtomicLong successfulHooks = new AtomicLong();
        private final AtomicLong failedHooks = new AtomicLong();
        private final AtomicLong timedOutHooks = new AtomicLong();
        private final AtomicLong quarantines = new AtomicLong();
        private final AtomicLong fence = new AtomicLong();

        private ManagedPlugin(InstalledPluginCandidate candidate) {
            this.candidate = candidate;
            machine = new PluginLifecycleMachine(candidate.pluginId());
            invocations = new InvocationAdmission(candidate.pluginId());
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
