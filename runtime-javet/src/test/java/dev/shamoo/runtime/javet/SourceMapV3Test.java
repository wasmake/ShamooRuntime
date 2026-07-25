package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.protocol.FilesystemPolicy;
import dev.shamoo.runtime.protocol.NodePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class SourceMapV3Test {
    @TempDir Path deployedPlugin;

    @Test
    void loadsAdjacentDeployedMapAndUsesNearestSegmentForStackColumns() throws Exception {
        Files.writeString(deployedPlugin.resolve("index.js.map"), """
                {"version":3,"sources":["src/plugin.ts"],"mappings":"AAAA"}
                """);
        NodePolicy policy = new NodePolicy(List.of(), new FilesystemPolicy(List.of(), List.of()),
                false, false, false, false);
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(new PluginId("mapped"), deployedPlugin, policy)) {
            SourceMapV3.registerAdjacent(runtime, deployedPlugin).toCompletableFuture().join();
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> runtime.evaluate("throw new Error('mapped');", "index.js").join());
            RuntimeEvaluationError error = assertInstanceOf(RuntimeEvaluationError.class, failure.getCause());
            assertEquals("src/plugin.ts", error.sourcePosition().resourceName());
            assertEquals(1, error.sourcePosition().line());
        }
    }

    @Test
    void registersLargeMapsWithoutExhaustingTheInvocationQueue() throws Exception {
        int mappingCount = 300;
        Files.writeString(deployedPlugin.resolve("index.js.map"), """
                {"version":3,"sources":["src/plugin.ts"],"mappings":"%s"}
                """.formatted(String.join(";", java.util.Collections.nCopies(mappingCount, "AAAA"))));
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        HostFunction block = arguments -> {
            entered.countDown();
            assertTrue(release.await(5, TimeUnit.SECONDS));
            return null;
        };
        NodePolicy policy = new NodePolicy(List.of(), new FilesystemPolicy(List.of(), List.of()),
                false, false, false, false);
        ShamooNodeRuntimeOptions limits =
                new ShamooNodeRuntimeOptions(1, Duration.ofSeconds(10), Duration.ofSeconds(5));
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(new PluginId("large-map"), deployedPlugin,
                policy, Map.of("block", block), limits, error -> { })) {
            CompletableFuture<Object> active = runtime.evaluate("host.block()", "active.js");
            assertTrue(entered.await(5, TimeUnit.SECONDS));
            CompletableFuture<Void> registration =
                    SourceMapV3.registerAdjacent(runtime, deployedPlugin).toCompletableFuture();

            release.countDown();
            active.join();
            registration.join();
            assertEquals(mappingCount, runtime.metrics().sourceMaps());
        } finally {
            release.countDown();
        }
    }

    @Test
    void rejectsMissingAdjacentMap() {
        NodePolicy policy = new NodePolicy(List.of(), new FilesystemPolicy(List.of(), List.of()),
                false, false, false, false);
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(new PluginId("missing"), deployedPlugin, policy)) {
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> SourceMapV3.registerAdjacent(runtime, deployedPlugin).toCompletableFuture().join());
            assertInstanceOf(IllegalArgumentException.class, failure.getCause());
        }
    }

    @Test
    void validationRejectsMalformedMappingSemanticsWithoutRuntime() throws Exception {
        Files.writeString(deployedPlugin.resolve("index.js.map"),
                "{\"version\":3,\"sources\":[\"src/plugin.ts\"],\"mappings\":\"A!AA\"}");

        assertThrows(IllegalArgumentException.class, () -> SourceMapV3.validateAdjacent(deployedPlugin));
    }
}
