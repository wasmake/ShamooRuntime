package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.RuntimeState;
import dev.shamoo.runtime.core.SourcePosition;
import dev.shamoo.runtime.protocol.FilesystemPolicy;
import dev.shamoo.runtime.protocol.NodePolicy;
import java.io.IOException;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.AvoidAccessibilityAlteration",
    "PMD.CloseResource",
    "PMD.SimplifiableTestAssertion",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UseTryWithResources"
})
class ShamooNodeRuntimeTest {
    @TempDir
    Path pluginRoot;

    @Test
    void isolatesGlobalsAndCommonJsModuleCaches() {
        try (ShamooNodeRuntimeManager manager = new ShamooNodeRuntimeManager()) {
            ShamooNodeRuntime first = create(manager, "first", policy(List.of(), List.of(), List.of()), Map.of());
            ShamooNodeRuntime second = create(manager, "second", policy(List.of(), List.of(), List.of()), Map.of());

            assertEquals(7, first.evaluate("globalThis.onlyHere = 7", "global.js").join());
            assertEquals("undefined", second.evaluate("typeof onlyHere", "global.js").join());
            String moduleSource = "globalThis.loads = (globalThis.loads || 0) + 1; module.exports = loads;";
            first.registerModule("counter.cjs", moduleSource, ModuleKind.COMMON_JS).join();
            second.registerModule("counter.cjs", moduleSource, ModuleKind.COMMON_JS).join();

            assertEquals(1, first.executeModule("counter.cjs").join());
            assertEquals(1, first.executeModule("counter.cjs").join());
            assertEquals(1, second.executeModule("counter.cjs").join());
        }
    }

    @Test
    void executesOnDedicatedOwnerThreadAndExposesOnlyExplicitCallbacks() {
        String callerThread = Thread.currentThread().getName();
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("callbacks"),
                pluginRoot,
                policy(List.of(), List.of(), List.of()),
                Map.of("threadName", arguments -> Thread.currentThread().getName(),
                    "add", arguments -> (Integer) arguments.get(0) + (Integer) arguments.get(1)),
                ShamooNodeRuntimeOptions.DEFAULT,
                error -> { })) {
            String ownerThread = (String) runtime.evaluate("host.threadName()", "callback.js").join();

            assertTrue(ownerThread.startsWith("shamoo-node-callbacks"));
            assertFalse(callerThread.equals(ownerThread));
            assertEquals(9, runtime.evaluate("host.add(4, 5)", "callback.js").join());
            assertEquals("undefined", runtime.evaluate("typeof Java", "callback.js").join());
            assertEquals("undefined", runtime.evaluate("typeof process", "callback.js").join());
            assertEquals("undefined", runtime.evaluate("typeof require", "callback.js").join());
            assertEquals("undefined", runtime.evaluate("typeof BroadcastChannel", "callback.js").join());
            assertEquals("undefined", runtime.evaluate("typeof MessageChannel", "callback.js").join());
            assertEquals("undefined", runtime.evaluate("typeof MessageEvent", "callback.js").join());
            assertEquals("undefined", runtime.evaluate("typeof MessagePort", "callback.js").join());
        }
    }

    @Test
    void exposesGlobalBufferOnlyWhenItsBuiltinIsAllowed() {
        try (ShamooNodeRuntime denied = ShamooNodeRuntime.create(
                new PluginId("buffer-denied"), pluginRoot, policy(List.of(), List.of(), List.of()));
                ShamooNodeRuntime allowed = ShamooNodeRuntime.create(
                    new PluginId("buffer-allowed"), pluginRoot,
                    policy(List.of("node:buffer"), List.of(), List.of()))) {
            allowed.registerModule("buffer.cjs",
                "module.exports = Buffer === require('node:buffer').Buffer;", ModuleKind.COMMON_JS).join();

            assertEquals("undefined", denied.evaluate("typeof Buffer", "buffer.js").join());
            assertEquals("function", allowed.evaluate("typeof Buffer", "buffer.js").join());
            assertEquals("6869", allowed.evaluate("Buffer.from('hi').toString('hex')", "buffer.js").join());
            assertEquals(true, allowed.executeModule("buffer.cjs").join());
        }
    }

    @Test
    void appliesBoundedQueueBackpressureWithoutSleeping() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HostFunction block = arguments -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return "released";
        };
        ShamooNodeRuntimeOptions limits =
            new ShamooNodeRuntimeOptions(1, Duration.ofSeconds(10), Duration.ofSeconds(5));
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("backpressure"), pluginRoot, policy(List.of(), List.of(), List.of()),
                Map.of("block", block), limits, error -> { })) {
            CompletableFuture<Object> active = runtime.evaluate("host.block()", "active.js");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Object> queued = runtime.evaluate("1", "queued.js");
            CompletableFuture<Object> rejected = runtime.evaluate("2", "rejected.js");

            CompletionException exception = assertThrows(CompletionException.class, rejected::join);
            assertInstanceOf(RuntimeQueueFullError.class, exception.getCause());
            assertEquals(1, runtime.metrics().activeInvocations());
            assertEquals(1, runtime.metrics().queuedInvocations());
            CompletableFuture<Void> closing = CompletableFuture.runAsync(runtime::close);
            CompletionException canceled = assertThrows(CompletionException.class, queued::join);
            assertInstanceOf(RuntimeDisposedError.class, canceled.getCause());
            release.countDown();
            assertEquals("released", active.join());
            closing.join();
            assertEquals(RuntimeState.CLOSED, runtime.state());
        } finally {
            release.countDown();
        }
    }

    @Test
    void drivesPromisesTimersAndReportsUnhandledFailures() {
        List<RuntimeUnhandledError> errors = new ArrayList<>();
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("promises"), pluginRoot, policy(List.of(), List.of(), List.of()), Map.of(),
                ShamooNodeRuntimeOptions.DEFAULT, errors::add)) {
            Object value = runtime.evaluate(
                "new Promise(resolve => setTimeout(() => resolve(42), 0))", "promise.js").join();
            assertEquals(42, value);

            assertEquals("ok", runtime.evaluate(
                "setTimeout(() => { throw new Error('timer failed') }, 0);"
                    + "new Promise(resolve => setTimeout(() => resolve('ok'), 1))",
                "timer.js").join());
            assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("timer failed")));
            runtime.evaluate("Promise.reject(new Error('rejected')); 'reported'", "rejection.js").join();
            assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("rejected")));
            assertTrue(runtime.metrics().unhandledErrors() >= 1);
        }
    }

    @Test
    void commonJsDrivesPromisesTimersAndReportsUnhandledFailures() {
        List<RuntimeUnhandledError> errors = new ArrayList<>();
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("commonjs-tasks"), pluginRoot, policy(List.of(), List.of(), List.of()), Map.of(),
                ShamooNodeRuntimeOptions.DEFAULT, errors::add)) {
            runtime.registerModule("promise.cjs",
                "module.exports = new Promise(resolve => setTimeout(() => resolve(42), 0));",
                ModuleKind.COMMON_JS).join();
            runtime.registerModule("rejection.cjs",
                "Promise.reject(new Error('commonjs rejected')); module.exports = 'reported';",
                ModuleKind.COMMON_JS).join();

            assertEquals(42, runtime.executeModule("promise.cjs").join());
            assertEquals("reported", runtime.executeModule("rejection.cjs").join());
            assertTrue(errors.stream().anyMatch(error -> error.getMessage().contains("commonjs rejected")));
        }
    }

    @Test
    void reportsAwaitedPromiseRejectionOnlyThroughEvaluation() {
        List<RuntimeUnhandledError> errors = new ArrayList<>();
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("awaited-rejection"), pluginRoot, policy(List.of(), List.of(), List.of()), Map.of(),
                ShamooNodeRuntimeOptions.DEFAULT, errors::add)) {
            CompletionException failure = assertThrows(CompletionException.class,
                () -> runtime.evaluate("Promise.reject(new Error('awaited'))", "awaited.js").join());

            assertInstanceOf(RuntimeEvaluationError.class, failure.getCause());
            assertEquals(0, errors.size());
            assertEquals(0, runtime.metrics().unhandledErrors());
        }
    }

    @Test
    void guardsCommonJsWrapperAndAllowsCloseAfterTermination() {
        ShamooNodeRuntimeOptions limits =
            new ShamooNodeRuntimeOptions(8, Duration.ofSeconds(2), Duration.ofSeconds(3));
        ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
            new PluginId("commonjs-timeout"), pluginRoot, policy(List.of(), List.of(), List.of()), Map.of(), limits,
            error -> { });
        runtime.registerModule("loop.cjs", "while (true) {}", ModuleKind.COMMON_JS).join();

        assertThrows(CompletionException.class, () -> runtime.executeModule("loop.cjs").join());
        runtime.close();
        assertEquals(RuntimeState.CLOSED, runtime.state());
    }

    @Test
    void rejectsSubMillisecondTimeoutsAndCleansUpFailedCreation() {
        assertThrows(IllegalArgumentException.class,
            () -> new ShamooNodeRuntimeOptions(1, Duration.ofNanos(1), Duration.ofSeconds(1)));
        assertThrows(RuntimeCreationError.class, () -> ShamooNodeRuntime.create(
            new PluginId("failed-create"), pluginRoot, policy(List.of(), List.of(), List.of()),
            Map.of("invalid-name", arguments -> null), ShamooNodeRuntimeOptions.DEFAULT, error -> { }));

        try (ShamooNodeRuntime replacement = ShamooNodeRuntime.create(
                new PluginId("failed-create"), pluginRoot, policy(List.of(), List.of(), List.of()))) {
            assertEquals(2, replacement.evaluate("1 + 1", "replacement.js").join());
        }
    }

    @Test
    void interruptedCreationWaitsForOwnerCleanupBeforeReturning() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<String, HostFunction> blockingBindings = new AbstractMap<>() {
            @Override
            public Set<Entry<String, HostFunction>> entrySet() {
                entered.countDown();
                boolean interrupted = false;
                while (true) {
                    try {
                        release.await();
                        break;
                    } catch (InterruptedException exception) {
                        interrupted = true;
                    }
                }
                if (interrupted) {
                    Thread.currentThread().interrupt();
                }
                return Set.of();
            }
        };
        AtomicReference<RuntimeException> failure = new AtomicReference<>();
        Thread creator = new Thread(() -> {
            try {
                ShamooNodeRuntime.create(
                    new PluginId("interrupted-create"), pluginRoot, policy(List.of(), List.of(), List.of()),
                    blockingBindings, ShamooNodeRuntimeOptions.DEFAULT, error -> { });
            } catch (RuntimeException exception) {
                failure.set(exception);
            }
        });
        creator.start();
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        creator.interrupt();
        creator.join(100);
        assertTrue(creator.isAlive());
        release.countDown();
        creator.join(5000);

        assertFalse(creator.isAlive());
        assertInstanceOf(RuntimeCreationError.class, failure.get());
        try (ShamooNodeRuntime replacement = ShamooNodeRuntime.create(
                new PluginId("interrupted-create"), pluginRoot, policy(List.of(), List.of(), List.of()))) {
            assertEquals(1, replacement.evaluate("1", "replacement.js").join());
        }
    }

    @Test
    void timedOutCreationWaitsForEventualOwnerCleanup() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        Map<String, HostFunction> blockingBindings = new AbstractMap<>() {
            @Override
            public Set<Entry<String, HostFunction>> entrySet() {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return Set.of();
            }
        };
        ShamooNodeRuntimeOptions limits =
            new ShamooNodeRuntimeOptions(1, Duration.ofMillis(50), Duration.ofSeconds(2));
        CompletableFuture<RuntimeException> creation = CompletableFuture.supplyAsync(() -> {
            try {
                ShamooNodeRuntime.create(
                    new PluginId("timed-create"), pluginRoot, policy(List.of(), List.of(), List.of()),
                    blockingBindings, limits, error -> { });
                return null;
            } catch (RuntimeException exception) {
                return exception;
            }
        });
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        assertThrows(TimeoutException.class, () -> creation.get(150, TimeUnit.MILLISECONDS));
        release.countDown();

        assertInstanceOf(RuntimeCreationError.class, creation.get(5, TimeUnit.SECONDS));
    }

    @Test
    void resolvesCommonJsRelativeToRequiringModule() {
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("relative-cjs"), pluginRoot, policy(List.of(), List.of(), List.of()))) {
            runtime.registerModule("lib/value.cjs", "module.exports = 41;", ModuleKind.COMMON_JS).join();
            runtime.registerModule("lib/entry.cjs",
                "module.exports = [require('./value.cjs') + 1, __dirname, __filename];",
                ModuleKind.COMMON_JS).join();

            assertEquals(List.of(42, "plugin:/lib", "plugin:/lib/entry.cjs"),
                runtime.executeModule("lib/entry.cjs").join());
        }
    }

    @Test
    void concurrentCloseCallersShareCompletionAndManagerBlocksReplacement() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ShamooNodeRuntimeManager manager = new ShamooNodeRuntimeManager();
        PluginId pluginId = new PluginId("shared-close");
        ShamooNodeRuntime runtime = manager.create(
            pluginId, pluginRoot, policy(List.of(), List.of(), List.of()),
            Map.of("block", arguments -> {
                entered.countDown();
                release.await(5, TimeUnit.SECONDS);
                return null;
            }), ShamooNodeRuntimeOptions.DEFAULT, error -> { });
        runtime.evaluate("host.block()", "block.js");
        assertTrue(entered.await(5, TimeUnit.SECONDS));
        CompletableFuture<Void> firstClose = CompletableFuture.runAsync(() -> manager.close(pluginId));
        while (runtime.state() != RuntimeState.CLOSING) {
            Thread.onSpinWait();
        }
        CompletableFuture<Void> secondClose = CompletableFuture.runAsync(runtime::close);
        CompletableFuture<ShamooNodeRuntime> replacement = CompletableFuture.supplyAsync(() -> manager.create(
            pluginId, pluginRoot, policy(List.of(), List.of(), List.of()), Map.of(),
            ShamooNodeRuntimeOptions.DEFAULT, error -> { }));

        assertThrows(TimeoutException.class, () -> firstClose.get(100, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> secondClose.get(100, TimeUnit.MILLISECONDS));
        assertThrows(TimeoutException.class, () -> replacement.get(100, TimeUnit.MILLISECONDS));
        release.countDown();
        firstClose.get(5, TimeUnit.SECONDS);
        secondClose.get(5, TimeUnit.SECONDS);
        try (ShamooNodeRuntime created = replacement.get(5, TimeUnit.SECONDS)) {
            assertEquals(1, created.evaluate("1", "created.js").join());
        } finally {
            manager.close();
            release.countDown();
        }
    }

    @Test
    void permitsOnlyMediatedBuiltinsAndDeniesNativeCapabilities() {
        NodePolicy policy = policy(List.of("node:path", "node:fs"), List.of(), List.of());
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(new PluginId("builtins"), pluginRoot, policy)) {
            runtime.registerModule("path.cjs", "module.exports = require('node:path').join('a', 'b');",
                ModuleKind.COMMON_JS).join();
            runtime.registerModule("fs.cjs", "module.exports = require('node:fs');", ModuleKind.COMMON_JS).join();
            runtime.registerModule(
                "path.mjs",
                "import path from 'node:path'; globalThis.esmPath = path.join('c', 'd');",
                ModuleKind.ESM).join();

            assertEquals("a/b", runtime.executeModule("path.cjs").join());
            runtime.executeModule("path.mjs").join();
            assertEquals("c/d", runtime.evaluate("esmPath", "esm-path.js").join());
            CompletionException denied = assertThrows(CompletionException.class,
                () -> runtime.executeModule("fs.cjs").join());
            assertInstanceOf(RuntimePermissionError.class, denied.getCause());
            assertEquals("undefined", runtime.evaluate("typeof fetch", "network.js").join());
        }
    }

    @Test
    void enforcesCanonicalFilesystemBoundaryAndTraversalDenial() throws Exception {
        Files.writeString(pluginRoot.resolve("allowed.txt"), "allowed");
        Files.writeString(pluginRoot.resolve("denied.txt"), "denied");
        Path outside = Files.createTempDirectory(pluginRoot.getParent(), "outside");
        Files.writeString(outside.resolve("secret.txt"), "secret");
        Files.createSymbolicLink(pluginRoot.resolve("link"), outside);
        NodePolicy policy = policy(List.of(), List.of("allowed.txt", "link"), List.of("data"));
        Files.createDirectory(pluginRoot.resolve("data"));
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(new PluginId("files"), pluginRoot, policy)) {
            assertEquals("allowed", runtime.readTextFile("allowed.txt").join());
            CompletionException traversal = assertThrows(CompletionException.class,
                () -> runtime.readTextFile("../outside.txt").join());
            assertInstanceOf(RuntimePermissionError.class, traversal.getCause());
            CompletionException denied = assertThrows(CompletionException.class,
                () -> runtime.readTextFile("denied.txt").join());
            assertInstanceOf(RuntimePermissionError.class, denied.getCause());
            CompletionException symlink = assertThrows(CompletionException.class,
                () -> runtime.readTextFile("link/secret.txt").join());
            assertInstanceOf(RuntimePermissionError.class, symlink.getCause());
            runtime.writeTextFile("data/output.txt", "written").join();
            assertEquals("written", Files.readString(pluginRoot.resolve("data/output.txt")));
        }
        Files.delete(pluginRoot.resolve("link"));
        Files.delete(outside.resolve("secret.txt"));
        Files.delete(outside);
    }

    @Test
    void filesystemSymlinkSwapNeverReadsOutsideRoot() throws Exception {
        Path outside = Files.createTempDirectory(pluginRoot.getParent(), "swap-outside");
        Files.writeString(outside.resolve("value.txt"), "secret");
        Path safe = pluginRoot.resolve("safe");
        Files.createDirectory(safe);
        Files.writeString(safe.resolve("value.txt"), "safe");
        Path slot = pluginRoot.resolve("slot");
        Files.createSymbolicLink(slot, safe.getFileName());
        AtomicBoolean running = new AtomicBoolean(true);
        Thread swapper = new Thread(() -> {
            while (running.get()) {
                try {
                    Files.deleteIfExists(slot);
                    Files.createSymbolicLink(slot, safe.getFileName());
                    Files.deleteIfExists(slot);
                    Files.createSymbolicLink(slot, outside);
                } catch (IOException ignored) {
                    // The runtime and swapper intentionally race on this directory entry.
                }
            }
        });
        swapper.start();
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("swap"), pluginRoot, policy(List.of(), List.of("slot"), List.of()))) {
            for (int index = 0; index < 100; index++) {
                try {
                    assertNotEquals("secret", runtime.readTextFile("slot/value.txt").join());
                } catch (CompletionException exception) {
                    assertInstanceOf(RuntimePermissionError.class, exception.getCause());
                }
            }
        } finally {
            running.set(false);
            swapper.join(5000);
            Files.deleteIfExists(slot);
            Files.delete(safe.resolve("value.txt"));
            Files.delete(safe);
            Files.delete(outside.resolve("value.txt"));
            Files.delete(outside);
        }
    }

    @Test
    void executesEsmWithControlledVirtualResolution() {
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("esm"), pluginRoot, policy(List.of(), List.of(), List.of()))) {
            runtime.registerModule("dependency.mjs", "export const answer = 42;", ModuleKind.ESM).join();
            runtime.registerModule(
                "entry.mjs",
                "import { answer } from './dependency.mjs'; globalThis.esmAnswer = answer;",
                ModuleKind.ESM)
                .join();

            runtime.executeModule("entry.mjs").join();
            assertEquals(42, runtime.evaluate("globalThis.esmAnswer", "verify.js").join());
            assertThrows(RuntimeModuleResolutionError.class,
                () -> runtime.registerModule("../escape.mjs", "export {};", ModuleKind.ESM));
        }
    }

    @Test
    void returnsStructuredMappedErrorsAndCleansUpDeterministically() {
        ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
            new PluginId("errors"), pluginRoot, policy(List.of(), List.of(), List.of()));
        runtime.registerSourceMap(
            new SourcePosition("plugin:/generated.js", 2, 1),
            new SourcePosition("src/original.ts", 10, 3)).join();

        CompletionException failure = assertThrows(CompletionException.class,
            () -> runtime.evaluate("\nthrow new Error('broken')", "generated.js").join());
        RuntimeEvaluationError error = assertInstanceOf(RuntimeEvaluationError.class, failure.getCause());
        assertEquals("src/original.ts", error.sourcePosition().resourceName());
        assertTrue(error.getCause() != null);

        runtime.close();
        assertEquals(RuntimeState.CLOSED, runtime.state());
        assertEquals(0, runtime.metrics().activeInvocations());
        assertEquals(0, runtime.metrics().registeredResources());
        CompletionException disposed = assertThrows(CompletionException.class,
            () -> runtime.evaluate("1", "closed.js").join());
        assertInstanceOf(RuntimeDisposedError.class, disposed.getCause());
        CompletionException sourceMapDisposed = assertThrows(CompletionException.class,
            () -> runtime.registerSourceMap(
                new SourcePosition("late.js", 1, 1), new SourcePosition("late.ts", 1, 1)).join());
        assertInstanceOf(RuntimeDisposedError.class, sourceMapDisposed.getCause());
    }

    @Test
    void cleanupFailuresDoNotSkipLaterResourcesOrNativeClose() throws Exception {
        ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
            new PluginId("cleanup-failure"), pluginRoot, policy(List.of(), List.of(), List.of()));
        Field resourcesField = ShamooNodeRuntime.class.getDeclaredField("resources");
        resourcesField.setAccessible(true);
        ResourceRegistry registry = (ResourceRegistry) resourcesField.get(runtime);
        AtomicBoolean laterResourceClosed = new AtomicBoolean();
        registry.register(() -> laterResourceClosed.set(true));
        registry.register(() -> {
            throw new IOException("expected cleanup failure");
        });

        assertThrows(IllegalStateException.class, runtime::close);
        assertTrue(laterResourceClosed.get());
        assertEquals(RuntimeState.FAILED, runtime.state());
        assertEquals(0, runtime.metrics().registeredResources());
        Field nodeRuntimeField = ShamooNodeRuntime.class.getDeclaredField("nodeRuntime");
        nodeRuntimeField.setAccessible(true);
        com.caoccao.javet.interop.NodeRuntime nativeRuntime =
            (com.caoccao.javet.interop.NodeRuntime) nodeRuntimeField.get(runtime);
        assertTrue(nativeRuntime.isClosed());
    }

    @Test
    void canonicalizesSourceMapNamesAndCompensatesCommonJsWrapperLine() {
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
                new PluginId("commonjs-map"), pluginRoot, policy(List.of(), List.of(), List.of()))) {
            runtime.registerModule("lib/generated.cjs", "throw new Error('mapped');", ModuleKind.COMMON_JS).join();
            runtime.registerSourceMap(
                new SourcePosition("lib/./generated.cjs", 1, 1),
                new SourcePosition("src/original.ts", 8, 4)).join();

            CompletionException failure = assertThrows(CompletionException.class,
                () -> runtime.executeModule("plugin:/lib/generated.cjs").join());
            RuntimeEvaluationError error = assertInstanceOf(RuntimeEvaluationError.class, failure.getCause());
            assertEquals(new SourcePosition("src/original.ts", 8, 4), error.sourcePosition());
        }
    }

    private ShamooNodeRuntime create(
            ShamooNodeRuntimeManager manager,
            String id,
            NodePolicy policy,
            Map<String, HostFunction> bindings) {
        return manager.create(
            new PluginId(id), pluginRoot, policy, bindings, ShamooNodeRuntimeOptions.DEFAULT, error -> { });
    }

    private static NodePolicy policy(List<String> builtins, List<String> read, List<String> write) {
        return new NodePolicy(builtins, new FilesystemPolicy(read, write), false, false, false, false);
    }
}
