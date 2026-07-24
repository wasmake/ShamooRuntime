package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.protocol.FilesystemPolicy;
import dev.shamoo.runtime.protocol.NodePolicy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class SourceMapV3Test {
    @TempDir Path deployedPlugin;

    @Test
    void loadsAdjacentDeployedMapAndUsesNearestSegmentForStackColumns() throws Exception {
        Files.createDirectories(deployedPlugin.resolve("paper"));
        Files.writeString(deployedPlugin.resolve("paper/index.js.map"), """
                {"version":3,"sources":["../src/plugin.ts"],"mappings":"AAAA"}
                """);
        NodePolicy policy = new NodePolicy(List.of(), new FilesystemPolicy(List.of(), List.of()),
                false, false, false, false);
        try (ShamooNodeRuntime runtime = ShamooNodeRuntime.create(new PluginId("mapped"), deployedPlugin, policy)) {
            SourceMapV3.registerAdjacent(runtime, deployedPlugin, "paper/index.js").toCompletableFuture().join();
            CompletionException failure = assertThrows(CompletionException.class,
                    () -> runtime.evaluate("throw new Error('mapped');", "paper/index.js").join());
            RuntimeEvaluationError error = assertInstanceOf(RuntimeEvaluationError.class, failure.getCause());
            assertEquals("src/plugin.ts", error.sourcePosition().resourceName());
            assertEquals(1, error.sourcePosition().line());
        }
    }
}
