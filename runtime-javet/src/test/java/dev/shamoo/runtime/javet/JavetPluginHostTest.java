package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.protocol.CompatibilityInput;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.SemanticVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.AvoidDuplicateLiterals"})
class JavetPluginHostTest {
    @TempDir
    Path plugins;

    @Test
    void runsTwoPluginsServicesReloadRollbackAndGenerationDisposal() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Path provider = plugin("provider",
                "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}", """
                const entrypoint = Object.freeze({
                  enable() {
                    host.record('provider-load');
                    host.registerCallback('service', async (operation, args) => args[0] + 1);
                    host.shamooProvideService('counter', '1.0.0', 'service');
                  },
                  exportHotState() { return new Uint8Array([7]); },
                  importHotState(value) { host.record('state-' + value[0]); },
                  unload() { host.record('provider-unload'); }
                });
                export { entrypoint as default };
                """);
        plugin("consumer", "{\"required\":{\"provider\":\"*\"},\"optional\":{},"
                + "\"loadBefore\":[],\"loadAfter\":[]}", """
                export async function ready() {
                  const handle = host.shamooAcquireService('counter', '^1.0.0', 'KEEP_RUNNING');
                  host.record('service-' + await host.shamooInvokeService(handle, 'increment', [41]));
                }
                export function unload() { host.record('consumer-unload'); }
                """);
        try (JavetPluginHost host = host(events)) {
            host.start(Duration.ofSeconds(30));
            assertEquals(2, host.runtimeCount());
            assertTrue(events.containsAll(List.of("provider-load", "service-42")));
            assertEquals(2, stagingEntries());

            Files.writeString(provider.resolve("index.mjs"), """
                    export function load() { host.record('provider-v2'); }
                    export function importHotState(value) { host.record('state-' + value[0]); }
                    export function exportHotState() { return new Uint8Array([8]); }
                    export function unload() { host.record('provider-v2-unload'); }
                    """);
            host.reload(new PluginId("provider")).toCompletableFuture().join();
            assertEquals(2, host.runtimeCount());
            assertTrue(events.containsAll(List.of("provider-v2", "state-7", "provider-unload")));
            assertEquals(2, stagingEntries());

            Files.writeString(provider.resolve("index.mjs"), """
                    export function ready() { throw new Error('candidate failed'); }
                    export function unload() { host.record('failed-candidate-unload'); }
                    """);
            assertThrows(CompletionException.class,
                    () -> host.reload(new PluginId("provider")).toCompletableFuture().join());
            assertEquals(2, host.runtimeCount());
            assertTrue(events.contains("failed-candidate-unload"));
            assertEquals(2, stagingEntries());

            host.disable(new PluginId("consumer")).toCompletableFuture().join();
            host.unload(new PluginId("consumer")).toCompletableFuture().join();
            assertEquals(1, host.runtimeCount());
            assertTrue(events.contains("consumer-unload"));
            assertEquals(1, stagingEntries());
            Files.writeString(plugins.resolve("consumer/index.mjs"),
                    "export default Object.freeze({enable(){host.record('consumer-reinstalled')}});\n");
            host.install(plugins.resolve("consumer")).toCompletableFuture().join();
            assertEquals(2, host.runtimeCount());
            assertEquals(2, stagingEntries());
            assertTrue(events.contains("consumer-reinstalled"));
        }
        assertEquals(0, stagingEntries());
    }

    private JavetPluginHost host(List<String> events) {
        SemanticVersion runtime = new SemanticVersion("0.1.0");
        CompatibilityInput input = new CompatibilityInput(PlatformKind.PAPER,
                new SemanticVersion("1.21.8"), new SemanticVersion("1.21.8"), null,
                Set.of(), runtime, runtime, ProtocolVersion.CURRENT);
        return new JavetPluginHost(plugins, input, PlatformCapabilities.NONE, Duration.ZERO,
                Duration.ofSeconds(3), Duration.ofSeconds(3),
                context -> Map.of("record", arguments -> {
                    events.add(String.valueOf(arguments.getFirst()));
                    return true;
                }), System.getLogger(getClass().getName()));
    }

    private Path plugin(String name, String dependencies, String source) throws Exception {
        Path root = Files.createDirectory(plugins.resolve(name));
        Files.writeString(root.resolve("index.mjs"), source);
        Files.writeString(root.resolve("shamoo-plugin.json"), """
                {"name":"%s","displayName":"%s","version":"1.0.0",
                "shamoo":{"api":"^0.1.0","runtime":"^0.1.0","manifest":1},
                "platforms":{"paper":{"enabled":true,"entrypoint":"index.mjs","minecraft":"1.21.x",
                "paperApi":"1.21.x"},"velocity":{"enabled":false}},
                "dependencies":%s,
                "node":{"builtins":[],"filesystem":{"read":[],"write":[]},"network":false,
                "workers":false,"childProcess":false,"nativeAddons":false},
                "reload":{"watch":true,"debounceMs":100,"preserveState":true}}
                """.formatted(name, name, dependencies));
        String communication = switch (name) {
            case "provider" -> "\"communication\":{\"services\":[{\"id\":\"counter\",\"version\":\"1.0.0\","
                    + "\"componentId\":\"provider\",\"methods\":[\"increment\"]}],\"events\":[],\"consumers\":[]},";
            case "consumer" -> "\"communication\":{\"services\":[],\"events\":[],\"consumers\":[{"
                    + "\"id\":\"counter\",\"versionRange\":\"^1.0.0\",\"dependentReload\":\"keep-running\"}]},";
            default -> "";
        };
        Files.writeString(root.resolve("shamoo.metadata.json"), """
                {"formatVersion":2,"compilerVersion":"test","packageName":"@fixture/%s",
                "components":[],"modules":[],%s"entrypoints":{"paper":{"source":"src/plugin.ts",
                "output":"index.mjs"}}}
                """.formatted(name, communication));
        return root;
    }

    private long stagingEntries() throws Exception {
        Path staging = plugins.resolve(".shamoo-staging");
        if (!Files.exists(staging)) {
            return 0;
        }
        try (var paths = Files.list(staging)) {
            return paths.count();
        }
    }
}
