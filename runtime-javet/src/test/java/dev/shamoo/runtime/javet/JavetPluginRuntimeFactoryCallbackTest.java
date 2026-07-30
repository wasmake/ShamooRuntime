package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.shamoo.runtime.core.InvocationAdmission;
import dev.shamoo.runtime.core.InvocationController;
import dev.shamoo.runtime.core.InvocationRejectedError;
import dev.shamoo.runtime.core.InvocationSnapshot;
import dev.shamoo.runtime.core.PlatformOperationResult;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.ScriptCallback;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.time.Duration;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CloseResource", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.UnitTestContainsTooManyAsserts"})
class JavetPluginRuntimeFactoryCallbackTest {
    @Test
    void recursivelyAdaptsOnlyExactMarkersAndCopiesPlainContainers() {
        ScriptCallback callback = values -> CompletableFuture.completedFuture(null);
        Map<String, Object> input = Map.of(
                "nested", List.of(Map.of("callback", Map.of("$callback", "action"))),
                "notMarker", Map.of("$callback", "plain", "extra", true));

        Object adaptedValue = JavetPluginRuntimeFactory.adaptCallbackMarkers(input, name -> callback);

        Map<?, ?> adapted = assertInstanceOf(Map.class, adaptedValue);
        List<?> nested = assertInstanceOf(List.class, adapted.get("nested"));
        Map<?, ?> holder = assertInstanceOf(Map.class, nested.getFirst());
        assertInstanceOf(ScriptCallback.class, holder.get("callback"));
        assertEquals(Map.of("$callback", "plain", "extra", true), adapted.get("notMarker"));
        assertNotSame(input, adapted);
    }

    @Test
    void rejectsExcessiveDepthAndNonDataObjects() {
        Object nested = "value";
        for (int index = 0; index < 34; index++) {
            nested = List.of(nested);
        }
        Object tooDeep = nested;

        assertThrows(IllegalArgumentException.class,
                () -> JavetPluginRuntimeFactory.adaptCallbackMarkers(tooDeep,
                        name -> values -> CompletableFuture.completedFuture(null)));
        assertThrows(IllegalArgumentException.class,
                () -> JavetPluginRuntimeFactory.adaptCallbackMarkers(new Object(),
                        name -> values -> CompletableFuture.completedFuture(null)));
    }

    @Test
    void eachHostArgumentStartsCallbackDepthAtZero() {
        Object nested = "value";
        for (int index = 0; index < 32; index++) {
            nested = List.of(nested);
        }

        List<Object> adapted = JavetPluginRuntimeFactory.adaptCallbackPayloads(List.of(nested),
                name -> values -> CompletableFuture.completedFuture(null));

        assertEquals(1, adapted.size());
    }

    @Test
    void adaptedCallbacksAccountUntilAsyncSettlementAndRejectAfterStop() {
        InvocationAdmission admission = new InvocationAdmission(new PluginId("fixture"));
        admission.open();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        AtomicBoolean closed = new AtomicBoolean();
        ScriptCallback callback = JavetPluginRuntimeFactory.admittedCallback(admission, new ScriptCallback() {
            @Override
            public CompletionStage<Object> invoke(List<Object> arguments) {
                return pending;
            }

            @Override
            public void close() {
                closed.set(true);
            }
        });

        CompletionStage<Object> invocation = callback.invoke(List.of());
        assertEquals(1, admission.snapshot().active());
        pending.complete("done");
        assertEquals("done", invocation.toCompletableFuture().join());
        assertEquals(0, admission.snapshot().active());
        assertEquals(1, admission.snapshot().completed());

        admission.stop();
        assertThrows(dev.shamoo.runtime.core.InvocationRejectedError.class, () -> callback.invoke(List.of()));
        assertEquals(1, admission.snapshot().rejected());
        callback.close();
        callback.close();
        assertTrue(closed.get());
    }

    @Test
    void synchronousCallbackFailureReleasesAdmissionLease() {
        InvocationAdmission admission = new InvocationAdmission(new PluginId("fixture"));
        admission.open();
        ScriptCallback callback = JavetPluginRuntimeFactory.admittedCallback(admission, arguments -> {
            throw new IllegalStateException("failed before returning a stage");
        });

        assertThrows(IllegalStateException.class, () -> callback.invoke(List.of()));
        assertEquals(0, admission.snapshot().active());
        assertEquals(1, admission.snapshot().completed());
    }

    @Test
    void recursivelyNormalizesAsyncResultsAndOwnsResourceExactlyOnce() throws Exception {
        ResourceRegistry resources = new ResourceRegistry();
        AtomicBoolean closed = new AtomicBoolean();
        CompletableFuture<Object> outer = new CompletableFuture<>();

        Object normalized = JavetPluginRuntimeFactory.normalizePlatformResult(
                outer, resources, new PluginId("fixture"), "operation");
        outer.complete(CompletableFuture.completedFuture(
                PlatformOperationResult.owned(false, () -> closed.set(true))));

        CompletionStage<?> stage = assertInstanceOf(CompletionStage.class, normalized);
        assertFalse((Boolean) stage.toCompletableFuture().join());
        assertEquals(1, resources.size());
        resources.closeAll();
        assertTrue(closed.get());
    }

    @Test
    void nonResourceCallbackOwnershipRollsBackFailuresAndOwnsSuccessfulData() {
        PluginId owner = new PluginId("fixture");
        ResourceRegistry resources = new ResourceRegistry();
        AtomicInteger closes = new AtomicInteger();
        ScriptCallback callback = callback(closes);

        assertFalse((Boolean) JavetPluginRuntimeFactory.normalizePlatformResult(
                false, resources, owner, "failed", List.of(callback)));
        assertEquals(1, closes.get());
        assertEquals(0, resources.size());

        assertEquals("data", JavetPluginRuntimeFactory.normalizePlatformResult(
                "data", resources, owner, "successful", List.of(callback(closes))));
        assertEquals(1, closes.get());
        assertEquals(1, resources.size());
        assertTrue(resources.cleanup(owner).clean());
        assertEquals(2, closes.get());
    }

    @Test
    void successfulCallbackOwnershipDeregistersAfterNaturalCallbackClose() {
        PluginId owner = new PluginId("fixture");
        InvocationAdmission admission = new InvocationAdmission(owner);
        ResourceRegistry resources = new ResourceRegistry();
        AtomicInteger closes = new AtomicInteger();
        ScriptCallback callback = JavetPluginRuntimeFactory.admittedCallback(admission, callback(closes));

        assertEquals("data", JavetPluginRuntimeFactory.normalizePlatformResult(
                "data", resources, owner, "successful", List.of(callback)));
        assertEquals(1, resources.size());

        callback.close();

        assertEquals(1, closes.get());
        assertEquals(0, resources.size());
    }

    @Test
    void platformOperationResourceTakesCallbackOwnershipWithoutDuplicateWrapper() {
        PluginId owner = new PluginId("fixture");
        ResourceRegistry resources = new ResourceRegistry();
        AtomicInteger closes = new AtomicInteger();
        ScriptCallback callback = callback(closes);

        assertTrue((Boolean) JavetPluginRuntimeFactory.normalizePlatformResult(
                PlatformOperationResult.owned(true, callback::close), resources, owner, "ui", List.of(callback)));
        assertEquals(0, closes.get());
        assertTrue(resources.cleanup(owner).clean());
        assertEquals(1, closes.get());
    }

    @Test
    void asynchronousCommandOperationLeaseDelaysDrainUntilResourceOwnershipSettles() {
        PluginId owner = new PluginId("fixture");
        InvocationAdmission admission = new InvocationAdmission(owner);
        ResourceRegistry resources = new ResourceRegistry();
        AtomicBoolean closed = new AtomicBoolean();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        admission.open();

        Object normalized = JavetPluginRuntimeFactory.normalizePlatformResult(
                pending, resources, owner, "paperCommandOpenInventory");
        Object admitted = JavetPluginRuntimeFactory.keepAdmissionUntilSettled(normalized, admission.admit());
        admission.stop();
        CompletionStage<Void> drained = admission.awaitDrained(Duration.ofSeconds(1));

        assertFalse(drained.toCompletableFuture().isDone());
        pending.complete(PlatformOperationResult.owned(true, () -> closed.set(true)));
        assertTrue((Boolean) assertInstanceOf(CompletionStage.class, admitted).toCompletableFuture().join());
        drained.toCompletableFuture().join();
        assertEquals(0, admission.snapshot().active());
        assertEquals(1, resources.size());

        assertTrue(resources.cleanup(owner).clean());
        assertEquals(0, resources.size());
        assertTrue(closed.get());
    }

    @Test
    void directHostBindingConditionallyLeasesOpenAdmissionUntilAsyncSettlement() throws Exception {
        PluginId owner = new PluginId("fixture");
        InvocationAdmission admission = new InvocationAdmission(owner);
        ResourceRegistry resources = new ResourceRegistry();
        CompletableFuture<Object> pending = new CompletableFuture<>();
        HostFunction binding = JavetPluginRuntimeFactory.managedHostBinding(
                ignored -> pending, resources, owner, "managed", admission);

        Object lifecycleResult = binding.invoke(List.of());
        assertEquals(0, admission.snapshot().active());
        pending.complete("initialized");
        assertEquals("initialized", assertInstanceOf(CompletionStage.class, lifecycleResult)
                .toCompletableFuture().join());

        CompletableFuture<Object> admittedPending = new CompletableFuture<>();
        admission.open();
        HostFunction admittedBinding = JavetPluginRuntimeFactory.managedHostBinding(
                ignored -> admittedPending, resources, owner, "managed", admission);
        Object admittedResult = admittedBinding.invoke(List.of());
        assertEquals(1, admission.snapshot().active());
        admission.stop();
        CompletionStage<Void> drained = admission.awaitDrained(Duration.ofSeconds(1));
        assertFalse(drained.toCompletableFuture().isDone());

        admittedPending.complete("ready");
        assertEquals("ready", assertInstanceOf(CompletionStage.class, admittedResult).toCompletableFuture().join());
        drained.toCompletableFuture().join();
        assertEquals(0, admission.snapshot().active());
    }

    @Test
    void directHostBindingPropagatesCapturedAdmissionUntilLeaseCloses() throws Exception {
        PluginId owner = new PluginId("fixture");
        InvocationAdmission admission = new InvocationAdmission(owner);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        AtomicBoolean capturedAdmission = new AtomicBoolean();
        HostFunction target = new HostFunction() {
            @Override
            public Object invoke(List<Object> arguments) {
                throw new AssertionError("managed wrapper must invoke the admission-aware method");
            }

            @Override
            public Object invoke(List<Object> arguments, boolean admitted) {
                capturedAdmission.set(admitted);
                return pending;
            }
        };
        admission.open();
        HostFunction binding = JavetPluginRuntimeFactory.managedHostBinding(
                target, new ResourceRegistry(), owner, "managed", admission);

        CompletionStage<?> result = assertInstanceOf(CompletionStage.class, binding.invoke(List.of()));
        assertTrue(capturedAdmission.get());
        assertEquals(1, admission.snapshot().active());
        admission.stop();
        CompletionStage<Void> drained = admission.awaitDrained(Duration.ofSeconds(1));
        assertFalse(drained.toCompletableFuture().isDone());

        pending.complete("settled");
        assertEquals("settled", result.toCompletableFuture().join());
        drained.toCompletableFuture().join();
        assertEquals(0, admission.snapshot().active());
        assertEquals(1, admission.snapshot().completed());
    }

    @Test
    void directHostBindingReleasesAdmissionAfterSynchronousFailure() {
        PluginId owner = new PluginId("fixture");
        InvocationAdmission admission = new InvocationAdmission(owner);
        admission.open();
        HostFunction binding = JavetPluginRuntimeFactory.managedHostBinding(ignored -> {
            throw new IllegalStateException("failed");
        }, new ResourceRegistry(), owner, "managed", admission);

        assertThrows(IllegalStateException.class, () -> binding.invoke(List.of()));
        assertEquals(0, admission.snapshot().active());
        assertEquals(1, admission.snapshot().completed());
    }

    @Test
    void directHostBindingReturnsFailedStageWhenAdmissionClosesBetweenSnapshotAndAdmit() throws Exception {
        PluginId owner = new PluginId("fixture");
        AtomicBoolean invoked = new AtomicBoolean();
        InvocationController racing = new InvocationController() {
            @Override
            public InvocationAdmission.Lease admit() {
                throw new InvocationRejectedError(owner);
            }

            @Override
            public InvocationSnapshot snapshot() {
                return new InvocationSnapshot(true, 0, 0, 0, 0);
            }
        };
        HostFunction binding = JavetPluginRuntimeFactory.managedHostBinding(ignored -> {
            invoked.set(true);
            return true;
        }, new ResourceRegistry(), owner, "managed", racing);

        CompletionStage<?> result = assertInstanceOf(CompletionStage.class, binding.invoke(List.of()));

        assertThrows(java.util.concurrent.CompletionException.class, () -> result.toCompletableFuture().join());
        assertFalse(invoked.get());
    }

    @Test
    void directHostBindingAllowsAdmissionClosedLifecycleHooksWithoutLeases() throws Exception {
        PluginId owner = new PluginId("fixture");
        InvocationAdmission admission = new InvocationAdmission(owner);
        AtomicInteger invokes = new AtomicInteger();
        HostFunction binding = JavetPluginRuntimeFactory.managedHostBinding(ignored -> {
            invokes.incrementAndGet();
            return CompletableFuture.completedFuture("initialized");
        }, new ResourceRegistry(), owner, "managed", admission);

        assertEquals("initialized", assertInstanceOf(CompletionStage.class, binding.invoke(List.of()))
                .toCompletableFuture().join());
        admission.open();
        admission.stop();
        CompletionStage<?> unload = assertInstanceOf(CompletionStage.class, binding.invoke(List.of()));

        assertEquals("initialized", unload.toCompletableFuture().join());
        assertEquals(2, invokes.get());
        assertEquals(0, admission.snapshot().active());
    }

    @Test
    void onlyAsynchronousCommandContextOperationsRequireAdmission() {
        for (String operation : List.of("paperCommandReply", "paperCommandOpenInventory",
                "paperCommandGiveItem", "paperCommandFindPlayer", "paperCommandMainHand",
                "paperCommandTakeMainHand")) {
            assertTrue(JavetPluginRuntimeFactory.requiresOperationAdmission(operation));
        }
        assertFalse(JavetPluginRuntimeFactory.requiresOperationAdmission("paperRegisterCommand"));
    }

    private static ScriptCallback callback(AtomicInteger closes) {
        return new ScriptCallback() {
            @Override
            public CompletionStage<Object> invoke(List<Object> values) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
    }
}
