package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import java.util.function.Supplier;
import dev.shamoo.runtime.protocol.ManifestCodec;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.CloseResource",
    "PMD.TestClassWithoutTestCases",
    "PMD.AvoidFieldNameMatchingMethodName",
    "PMD.AvoidDuplicateLiterals",
    "PMD.AssignmentInOperand",
    "PMD.LiteralsFirstInComparisons"
})
class PluginLifecycleCoordinatorTest {
    private static final Duration TIMEOUT = Duration.ofSeconds(2);
    private final Executor executor = Executors.newCachedThreadPool();

    @Test
    void serializesConcurrentCallsAndMakesCompletedPhasesIdempotent() {
        AtomicInteger loads = new AtomicInteger();
        TestRuntime runtime = new TestRuntime();
        runtime.load = () -> CompletableFuture.runAsync(() -> {
            loads.incrementAndGet();
            sleep(50);
        });
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(runtime));
        PluginId id = installOne(coordinator);
        CompletableFuture<Void> first = coordinator.load(id, UUID.randomUUID()).toCompletableFuture();
        CompletableFuture<Void> second = coordinator.load(id, UUID.randomUUID()).toCompletableFuture();
        CompletableFuture.allOf(first, second).join();
        assertEquals(1, loads.get());
        assertEquals(PluginLifecycleState.LOADED, coordinator.snapshot(id).state());
    }

    @Test
    void runsHooksThroughReadyThenWaitsForActiveInvocationDrain() {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(events, "plugin");
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(runtime));
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        InvocationAdmission.Lease lease = coordinator.admitInvocation(id);
        CompletableFuture<Void> disabling = coordinator.disable(id, UUID.randomUUID()).toCompletableFuture();
        sleep(50);
        assertEquals(PluginLifecycleState.DRAINING, coordinator.snapshot(id).state());
        lease.close();
        disabling.join();
        assertEquals(List.of("plugin:load", "plugin:enable", "plugin:ready", "plugin:drain", "plugin:disable"),
                events);
        assertEquals(PluginLifecycleState.DISABLED, coordinator.snapshot(id).state());
        assertEquals(1, coordinator.snapshot(id).invocations().completed());
    }

    @Test
    void recordsExactFailureStateAndQuarantinesOnlyAtPolicyThreshold() {
        TestRuntime runtime = new TestRuntime();
        runtime.load = () -> CompletableFuture.failedFuture(new IllegalStateException("broken"));
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(
                context -> CompletableFuture.completedFuture(runtime), new ResourceRegistry(), TIMEOUT, TIMEOUT,
                new QuarantinePolicy(2, true), executor);
        PluginId id = installOne(coordinator);
        assertThrows(CompletionException.class,
                () -> coordinator.load(id, UUID.randomUUID()).toCompletableFuture().join());
        assertEquals(PluginLifecycleState.LOAD_FAILED, coordinator.snapshot(id).state());
        CompletionException second = assertThrows(CompletionException.class,
                () -> coordinator.load(id, UUID.randomUUID()).toCompletableFuture().join());
        assertInstanceOf(PluginLoadError.class, second.getCause());
        assertEquals(PluginLifecycleState.QUARANTINED, coordinator.snapshot(id).state());
        assertEquals(List.of(PluginLifecycleState.LOAD_FAILED, PluginLifecycleState.LOAD_FAILED,
                        PluginLifecycleState.QUARANTINED),
                coordinator.snapshot(id).transitions().stream()
                        .map(LifecycleTransition::to)
                        .filter(state -> state == PluginLifecycleState.LOAD_FAILED
                                || state == PluginLifecycleState.QUARANTINED).toList());
    }

    @Test
    void timeoutProducesDrainFailedAndRetryIsLegal() {
        TestRuntime runtime = new TestRuntime();
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(
                context -> CompletableFuture.completedFuture(runtime), new ResourceRegistry(),
                Duration.ofSeconds(1), Duration.ofMillis(30), new QuarantinePolicy(3, true), executor);
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        InvocationAdmission.Lease lease = coordinator.admitInvocation(id);
        CompletionException failure = assertThrows(CompletionException.class,
                () -> coordinator.disable(id, UUID.randomUUID()).toCompletableFuture().join());
        assertInstanceOf(LifecycleTimeoutError.class, failure.getCause());
        assertEquals(PluginLifecycleState.DRAIN_FAILED, coordinator.snapshot(id).state());
        lease.close();
        coordinator.disable(id, UUID.randomUUID()).toCompletableFuture().join();
        assertEquals(PluginLifecycleState.DISABLED, coordinator.snapshot(id).state());
        assertEquals(1, coordinator.snapshot(id).metrics().timedOutHooks());
    }

    @Test
    void quarantinedUnloadWaitsForActiveInvocations() {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        TestRuntime runtime = new TestRuntime(events, "plugin");
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(
                context -> CompletableFuture.completedFuture(runtime), new ResourceRegistry(),
                TIMEOUT, Duration.ofMillis(300), new QuarantinePolicy(1, true), executor);
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        InvocationAdmission.Lease lease = coordinator.admitInvocation(id);
        assertThrows(CompletionException.class,
                () -> coordinator.disable(id, UUID.randomUUID()).toCompletableFuture().join());
        assertEquals(PluginLifecycleState.QUARANTINED, coordinator.snapshot(id).state());

        CompletableFuture<Void> unloading = coordinator.unload(id, UUID.randomUUID()).toCompletableFuture();
        await(() -> coordinator.snapshot(id).state() == PluginLifecycleState.UNLOADING);
        assertFalse(unloading.isDone());
        assertFalse(events.contains("plugin:unload"));
        lease.close();
        unloading.join();
        assertEquals(PluginLifecycleState.UNLOADED, coordinator.snapshot(id).state());
    }

    @Test
    void timedOutHookIsCancelled() {
        NonCancellableFuture<Void> load = new NonCancellableFuture<>();
        TestRuntime runtime = new TestRuntime();
        runtime.load = () -> load;
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(
                context -> CompletableFuture.completedFuture(runtime), new ResourceRegistry(),
                Duration.ofMillis(30), TIMEOUT, QuarantinePolicy.DEFAULT, executor);
        PluginId id = installOne(coordinator);
        assertThrows(CompletionException.class,
                () -> coordinator.load(id, UUID.randomUUID()).toCompletableFuture().join());
        assertTrue(load.cancelAttempted.get());
        load.complete(null);
        assertEquals(PluginLifecycleState.LOAD_FAILED, coordinator.snapshot(id).state());
    }

    @Test
    void lateRuntimeCreationIsFencedAndUnloaded() {
        NonCancellableFuture<PluginRuntime> creation = new NonCancellableFuture<>();
        TestRuntime staleRuntime = new TestRuntime();
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(
                context -> creation, new ResourceRegistry(), Duration.ofMillis(30), TIMEOUT,
                QuarantinePolicy.DEFAULT, executor);
        PluginId id = installOne(coordinator);
        assertThrows(CompletionException.class,
                () -> coordinator.load(id, UUID.randomUUID()).toCompletableFuture().join());
        assertTrue(creation.cancelAttempted.get());
        creation.complete(staleRuntime);
        await(() -> staleRuntime.events.contains("plugin:unload"));
        assertEquals(PluginLifecycleState.LOAD_FAILED, coordinator.snapshot(id).state());
    }

    @Test
    void enableAndDisableAllFollowOppositeDependencyOrders() {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        Map<PluginId, TestRuntime> runtimes = Map.of(
                new PluginId("base"), new TestRuntime(events, "base"),
                new PluginId("dependent"), new TestRuntime(events, "dependent"));
        PluginLifecycleCoordinator coordinator = coordinator(
                context -> CompletableFuture.completedFuture(runtimes.get(context.candidate().pluginId())));
        InstalledPluginCandidate base = TestCandidates.candidate("base");
        InstalledPluginCandidate dependent = TestCandidates.candidate("dependent", "1.0.0", """
                {"required":{"base":"*"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        coordinator.install(List.of(dependent, base));
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        coordinator.disableAll(UUID.randomUUID()).toCompletableFuture().join();
        assertTrue(events.indexOf("base:enable") < events.indexOf("dependent:enable"));
        assertTrue(events.indexOf("dependent:disable") < events.indexOf("base:disable"));
    }

    @Test
    void dependencyDisableWaitsForDependentEnablingToFinish() {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        TestRuntime baseRuntime = new TestRuntime(events, "base");
        TestRuntime dependentRuntime = new TestRuntime(events, "dependent");
        CompletableFuture<Void> enabling = new CompletableFuture<>();
        dependentRuntime.enableHook = () -> enabling;
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(
                context.candidate().pluginId().equals(new PluginId("base")) ? baseRuntime : dependentRuntime));
        InstalledPluginCandidate base = TestCandidates.candidate("base");
        InstalledPluginCandidate dependent = TestCandidates.candidate("dependent", "1.0.0", """
                {"required":{"base":"*"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        coordinator.install(List.of(base, dependent));
        coordinator.load(base.pluginId(), UUID.randomUUID()).toCompletableFuture().join();
        coordinator.enable(base.pluginId(), UUID.randomUUID()).toCompletableFuture().join();
        coordinator.ready(base.pluginId(), UUID.randomUUID()).toCompletableFuture().join();
        coordinator.load(dependent.pluginId(), UUID.randomUUID()).toCompletableFuture().join();

        CompletableFuture<Void> dependentEnable = coordinator.enable(
                dependent.pluginId(), UUID.randomUUID()).toCompletableFuture();
        await(() -> coordinator.snapshot(dependent.pluginId()).state() == PluginLifecycleState.ENABLING);
        CompletableFuture<Void> baseDisable = coordinator.disable(
                base.pluginId(), UUID.randomUUID()).toCompletableFuture();
        sleep(50);
        assertFalse(baseDisable.isDone());
        assertFalse(events.contains("base:drain"));
        enabling.complete(null);
        CompletableFuture.allOf(dependentEnable, baseDisable).join();
        assertTrue(events.indexOf("dependent:disable") < events.indexOf("base:disable"));
    }

    @Test
    void loadFailedRuntimeCanBeUnloadedAndCleaned() {
        TestRuntime runtime = new TestRuntime();
        runtime.load = failed("load");
        PluginLifecycleCoordinator coordinator = coordinator(
                context -> CompletableFuture.completedFuture(runtime));
        PluginId id = installOne(coordinator);
        assertThrows(CompletionException.class,
                () -> coordinator.load(id, UUID.randomUUID()).toCompletableFuture().join());
        coordinator.unload(id, UUID.randomUUID()).toCompletableFuture().join();
        assertEquals(List.of("plugin:load", "plugin:unload"), runtime.events);
        assertEquals(PluginLifecycleState.UNLOADED, coordinator.snapshot(id).state());
    }

    @Test
    void failedInstallDoesNotPartiallyMutateCandidatesOrResolution() {
        PluginLifecycleCoordinator coordinator = coordinator(
                context -> CompletableFuture.completedFuture(new TestRuntime()));
        InstalledPluginCandidate original = TestCandidates.candidate("base");
        coordinator.install(List.of(original));
        InstalledPluginCandidate replacement = TestCandidates.candidate(
                "base", "2.0.0", TestCandidates.emptyDependencies());
        assertThrows(IllegalStateException.class,
                () -> coordinator.install(List.of(TestCandidates.candidate("added"), replacement)));
        assertEquals(List.of(original.pluginId()),
                coordinator.snapshots().stream().map(PluginIntrospectionSnapshot::pluginId).toList());
        assertThrows(IllegalArgumentException.class,
                () -> coordinator.snapshot(new PluginId("added")));
    }

    @Test
    void cleanupLeakFailsUnloadAndAppliesLeakQuarantinePolicy() {
        ResourceRegistry resources = new ResourceRegistry();
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(
                context -> CompletableFuture.completedFuture(new TestRuntime()), resources, TIMEOUT, TIMEOUT,
                new QuarantinePolicy(5, true), executor);
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        coordinator.disable(id, UUID.randomUUID()).toCompletableFuture().join();
        resources.register(id, ResourceCategory.SERVICE, "leak", () -> {
            throw new IllegalStateException("still in use");
        });
        assertThrows(CompletionException.class,
                () -> coordinator.unload(id, UUID.randomUUID()).toCompletableFuture().join());
        assertEquals(PluginLifecycleState.QUARANTINED, coordinator.snapshot(id).state());
        assertEquals(1, coordinator.snapshot(id).resources().size());
    }

    @Test
    void failedHooksEnterTheirExactPhaseStates() {
        assertHookFailure(PluginLifecycleState.ENABLE_FAILED, runtime ->
                runtime.enableHook = failed("enable"), coordinator -> {
                    PluginId id = installOne(coordinator);
                    coordinator.load(id, UUID.randomUUID()).toCompletableFuture().join();
                    return coordinator.enable(id, UUID.randomUUID());
                });
        assertHookFailure(PluginLifecycleState.READY_FAILED, runtime ->
                runtime.readyHook = failed("ready"), coordinator -> {
                    PluginId id = installOne(coordinator);
                    coordinator.load(id, UUID.randomUUID()).toCompletableFuture().join();
                    coordinator.enable(id, UUID.randomUUID()).toCompletableFuture().join();
                    return coordinator.ready(id, UUID.randomUUID());
                });
        assertHookFailure(PluginLifecycleState.DISABLE_FAILED, runtime ->
                runtime.disableHook = failed("disable"), coordinator -> {
                    PluginId id = installOne(coordinator);
                    coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
                    return coordinator.disable(id, UUID.randomUUID());
                });
        assertHookFailure(PluginLifecycleState.UNLOAD_FAILED, runtime ->
                runtime.unloadHook = failed("unload"), coordinator -> {
                    PluginId id = installOne(coordinator);
                    coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
                    coordinator.disable(id, UUID.randomUUID()).toCompletableFuture().join();
                    return coordinator.unload(id, UUID.randomUUID());
                });
    }

    @Test
    void failedCandidatePreservesReadyActiveRuntime() {
        TestRuntime active = new TestRuntime();
        TestRuntime candidate = new TestRuntime();
        candidate.readyHook = failed("candidate ready");
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(
                context.candidate().descriptor().version().value().equals("1.0.0") ? active : candidate));
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();

        CompletionException failure = assertThrows(CompletionException.class, () -> coordinator.replace(
                TestCandidates.candidate("plugin", "2.0.0", TestCandidates.emptyDependencies()),
                UUID.randomUUID()).toCompletableFuture().join());

        assertInstanceOf(PluginReadyError.class, failure.getCause());
        assertEquals(PluginLifecycleState.READY, coordinator.snapshot(id).state());
        assertFalse(active.events.contains("plugin:drain"));
        assertFalse(active.events.contains("plugin:disable"));
        assertEquals(List.of("plugin:load", "plugin:enable", "plugin:ready", "plugin:unload"), candidate.events);
        InvocationAdmission.Lease lease = coordinator.admitInvocation(id);
        assertTrue(coordinator.snapshot(id).invocations().accepting());
        lease.close();
    }

    @Test
    void migratesHotStateBeforeDrainingAndAtomicallyAdmitsReplacement() {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        StatefulRuntime active = new StatefulRuntime(events, "old", new byte[] {1, 2, 3});
        StatefulRuntime candidate = new StatefulRuntime(events, "new", new byte[0]);
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(
                context.candidate().descriptor().version().value().equals("1.0.0") ? active : candidate));
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();

        coordinator.replace(preserveStateCandidate("plugin", "2.0.0"), UUID.randomUUID())
                .toCompletableFuture().join();

        assertArrayEquals(new byte[] {1, 2, 3}, candidate.state);
        assertTrue(events.indexOf("old:export") < events.indexOf("new:import"));
        assertTrue(events.indexOf("new:import") < events.indexOf("old:drain"));
        assertTrue(events.indexOf("old:drain") < events.indexOf("old:unload"));
        assertEquals(PluginLifecycleState.READY, coordinator.snapshot(id).state());
        InvocationAdmission.Lease lease = coordinator.admitInvocation(id);
        assertTrue(coordinator.snapshot(id).invocations().accepting());
        lease.close();
    }

    @Test
    void failedStateImportPreservesActiveRuntimeAndAdmission() {
        List<String> events = java.util.Collections.synchronizedList(new ArrayList<>());
        StatefulRuntime active = new StatefulRuntime(events, "old", new byte[] {4, 5, 6});
        StatefulRuntime candidate = new StatefulRuntime(events, "new", new byte[0]);
        candidate.failImport = true;
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(
                "1.0.0".equals(context.candidate().descriptor().version().value()) ? active : candidate));
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();

        assertThrows(CompletionException.class, () -> coordinator.replace(
                preserveStateCandidate("plugin", "2.0.0"), UUID.randomUUID()).toCompletableFuture().join());

        assertEquals(PluginLifecycleState.READY, coordinator.snapshot(id).state());
        assertFalse(events.contains("old:drain"));
        assertTrue(events.contains("new:unload"));
        InvocationAdmission.Lease lease = coordinator.admitInvocation(id);
        lease.close();
    }

    @Test
    void reportsRetiredRuntimeResourceLeakWithoutRollingBackSuccessfulSwap() {
        ResourceRegistry resources = new ResourceRegistry();
        TestRuntime active = new TestRuntime();
        TestRuntime candidate = new TestRuntime();
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(context -> {
            if (context.candidate().descriptor().version().value().equals("1.0.0")) {
                context.resources().register(context.candidate().pluginId(), ResourceCategory.SERVICE,
                        "old generation", () -> {
                            throw new IllegalStateException("leaked");
                        });
                return CompletableFuture.completedFuture(active);
            }
            return CompletableFuture.completedFuture(candidate);
        }, resources, TIMEOUT, TIMEOUT, QuarantinePolicy.DEFAULT, executor);
        PluginId id = installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();

        CompletionException failure = assertThrows(CompletionException.class, () -> coordinator.replace(
                TestCandidates.candidate("plugin", "2.0.0", TestCandidates.emptyDependencies()),
                UUID.randomUUID()).toCompletableFuture().join());

        assertInstanceOf(ResourceCleanupError.class, failure.getCause());
        assertEquals(PluginLifecycleState.READY, coordinator.snapshot(id).state());
        assertEquals("old generation", coordinator.snapshot(id).resources().getFirst().description());
        InvocationAdmission.Lease lease = coordinator.admitInvocation(id);
        assertTrue(candidate.events.contains("plugin:ready"));
        lease.close();
    }

    @Test
    void stagesCandidateBeforeReplacement() {
        TestRuntime active = new TestRuntime();
        TestRuntime candidate = new TestRuntime();
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(
                context.candidate().descriptor().version().value().equals("1.0.0") ? active : candidate));
        installOne(coordinator);
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        java.nio.file.Path source = java.nio.file.Path.of("source");
        java.nio.file.Path staging = java.nio.file.Path.of("staging");
        AtomicBoolean staged = new AtomicBoolean();

        coordinator.stageAndReplace(source, staging, (actualSource, actualStaging) -> {
            assertEquals(source, actualSource);
            assertEquals(staging, actualStaging);
            staged.set(true);
            return TestCandidates.candidate("plugin", "2.0.0", TestCandidates.emptyDependencies());
        }, UUID.randomUUID()).toCompletableFuture().join();

        assertTrue(staged.get());
        assertTrue(candidate.events.contains("plugin:ready"));
    }

    @Test
    void rejectsReplacementThatWouldBlockAnActiveDependent() {
        Map<String, TestRuntime> runtimes = new java.util.HashMap<>();
        PluginLifecycleCoordinator coordinator = coordinator(context -> CompletableFuture.completedFuture(
                runtimes.computeIfAbsent(context.candidate().pluginId() + "-"
                        + context.candidate().descriptor().version().value(), ignored -> new TestRuntime())));
        InstalledPluginCandidate base = TestCandidates.candidate("base");
        InstalledPluginCandidate dependent = TestCandidates.candidate("dependent", "1.0.0", """
                {"required":{"base":"^1.0.0"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        coordinator.install(List.of(base, dependent));
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();

        assertThrows(CompletionException.class, () -> coordinator.replace(
                TestCandidates.candidate("base", "2.0.0", TestCandidates.emptyDependencies()),
                UUID.randomUUID()).toCompletableFuture().join());

        assertEquals(PluginLifecycleState.READY, coordinator.snapshot(base.pluginId()).state());
        assertEquals(PluginLifecycleState.READY, coordinator.snapshot(dependent.pluginId()).state());
        InvocationAdmission.Lease baseLease = coordinator.admitInvocation(base.pluginId());
        InvocationAdmission.Lease dependentLease = coordinator.admitInvocation(dependent.pluginId());
        baseLease.close();
        dependentLease.close();
    }

    private void assertHookFailure(
            PluginLifecycleState expected,
            java.util.function.Consumer<TestRuntime> configure,
            Function<PluginLifecycleCoordinator, CompletionStage<Void>> action) {
        TestRuntime runtime = new TestRuntime();
        configure.accept(runtime);
        PluginLifecycleCoordinator coordinator = coordinator(
                context -> CompletableFuture.completedFuture(runtime));
        assertThrows(CompletionException.class, () -> action.apply(coordinator).toCompletableFuture().join());
        assertEquals(expected, coordinator.snapshots().getFirst().state());
    }

    private static Supplier<CompletableFuture<Void>> failed(String phase) {
        return () -> CompletableFuture.failedFuture(new IllegalStateException(phase));
    }

    private static InstalledPluginCandidate preserveStateCandidate(String id, String version) {
        InstalledPluginCandidate candidate = TestCandidates.candidate(
                id, version, TestCandidates.emptyDependencies());
        ManifestCodec codec = new ManifestCodec();
        String manifest = codec.serialize(candidate.descriptor())
                .replace("\"preserveState\":false", "\"preserveState\":true");
        return new InstalledPluginCandidate(candidate.pluginId(), codec.parse(manifest),
                candidate.root(), candidate.checksums());
    }

    private PluginLifecycleCoordinator coordinator(PluginRuntimeFactory factory) {
        return new PluginLifecycleCoordinator(factory, new ResourceRegistry(), TIMEOUT, TIMEOUT,
                QuarantinePolicy.DEFAULT, executor);
    }

    private static PluginId installOne(PluginLifecycleCoordinator coordinator) {
        InstalledPluginCandidate candidate = TestCandidates.candidate("plugin");
        coordinator.install(List.of(candidate));
        return candidate.pluginId();
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(exception);
        }
    }

    private static void await(Supplier<Boolean> condition) {
        long deadline = System.nanoTime() + TIMEOUT.toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            sleep(5);
        }
        assertTrue(condition.get());
    }

    private static final class NonCancellableFuture<T> extends CompletableFuture<T> {
        private final AtomicBoolean cancelAttempted = new AtomicBoolean();

        @Override
        public boolean cancel(boolean mayInterruptIfRunning) {
            cancelAttempted.set(true);
            return false;
        }
    }

    private static class TestRuntime implements PluginRuntime {
        protected final List<String> events;
        protected final String name;
        private Supplier<CompletableFuture<Void>> load = () -> CompletableFuture.completedFuture(null);
        private Supplier<CompletableFuture<Void>> enableHook = () -> CompletableFuture.completedFuture(null);
        private Supplier<CompletableFuture<Void>> readyHook = () -> CompletableFuture.completedFuture(null);
        private Supplier<CompletableFuture<Void>> disableHook = () -> CompletableFuture.completedFuture(null);
        private Supplier<CompletableFuture<Void>> unloadHook = () -> CompletableFuture.completedFuture(null);

        private TestRuntime() {
            this(new ArrayList<>(), "plugin");
        }

        private TestRuntime(List<String> events, String name) {
            this.events = events;
            this.name = name;
        }

        @Override
        public CompletableFuture<Void> load() {
            events.add(name + ":load");
            return load.get();
        }

        @Override
        public CompletableFuture<Void> enable() {
            events.add(name + ":enable");
            return enableHook.get();
        }

        @Override
        public CompletableFuture<Void> ready() {
            events.add(name + ":ready");
            return readyHook.get();
        }

        @Override
        public CompletableFuture<Void> drain() {
            events.add(name + ":drain");
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public CompletableFuture<Void> disable() {
            events.add(name + ":disable");
            return disableHook.get();
        }

        @Override
        public CompletableFuture<Void> unload() {
            events.add(name + ":unload");
            return unloadHook.get();
        }
    }

    private static final class StatefulRuntime extends TestRuntime implements HotStatePluginRuntime {
        private byte[] state;
        private boolean failImport;

        private StatefulRuntime(List<String> events, String name, byte[] state) {
            super(events, name);
            this.state = state.clone();
        }

        @Override
        public CompletionStage<byte[]> exportHotState() {
            events.add(name + ":export");
            return CompletableFuture.completedFuture(state.clone());
        }

        @Override
        public CompletionStage<Void> importHotState(byte[] imported) {
            events.add(name + ":import");
            if (failImport) {
                return CompletableFuture.failedFuture(new IllegalStateException("state import failed"));
            }
            state = imported.clone();
            return CompletableFuture.completedFuture(null);
        }
    }
}
