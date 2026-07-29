package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.PlatformOperationResult;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.AvoidDuplicateLiterals"})
class JavetPluginHostTest {
    private static final int FIRST_REQUEST_COUNT = 1;
    @TempDir
    Path plugins;

    @Test
    void startsAsynchronouslyWhilePreservingDependencyOrder() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        CompletableFuture<Boolean> firstLoad = new CompletableFuture<>();
        plugin("first", "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}", """
                export async function load() {
                  host.record('first-start');
                  await host.waitForFirst();
                  host.record('first-end');
                }
                """);
        plugin("second", "{\"required\":{\"first\":\"*\"},\"optional\":{},"
                + "\"loadBefore\":[],\"loadAfter\":[]}", """
                export function load() { host.record('second-start'); }
                """);
        SemanticVersion runtime = new SemanticVersion("0.1.0");
        CompatibilityInput input = new CompatibilityInput(PlatformKind.PAPER,
                new SemanticVersion("1.21.8"), new SemanticVersion("1.21.8"), null,
                Set.of(), runtime, runtime, ProtocolVersion.CURRENT);
        try (JavetPluginHost host = new JavetPluginHost(plugins, input, PlatformCapabilities.NONE, Duration.ZERO,
                Duration.ofSeconds(3), context -> Map.of(
                        "record", arguments -> {
                            events.add(String.valueOf(arguments.getFirst()));
                            return true;
                        },
                        "waitForFirst", arguments -> firstLoad), System.getLogger(getClass().getName()))) {
            var startup = host.startAsync(Duration.ofSeconds(30));

            await(() -> events.contains("first-start"));
            assertFalse(startup.toCompletableFuture().isDone());
            assertFalse(events.contains("second-start"));

            firstLoad.complete(true);
            startup.toCompletableFuture().join();

            assertEquals(List.of("first-start", "first-end", "second-start"), events);
            assertThrows(IllegalStateException.class, () -> host.startAsync(Duration.ofSeconds(30)));
        }
    }

    @Test
    void managesAsyncBootstrapBindingResourcesWithoutCompilerMetadataAndRollsBackFailedGeneration()
            throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        List<List<Object>> requests = new CopyOnWriteArrayList<>();
        AtomicInteger closes = new AtomicInteger();
        CompletableFuture<PlatformOperationResult<Map<String, Object>>> firstRegistration = new CompletableFuture<>();
        Path managed = plugin("managed-binding",
                "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}", """
                export async function load() {
                  const result = await host.customBinding({world: 'spawn'});
                  host.record(result.status + ':' + result.world);
                }
                """);
        SemanticVersion runtime = new SemanticVersion("0.1.0");
        CompatibilityInput input = new CompatibilityInput(PlatformKind.PAPER,
                new SemanticVersion("1.21.8"), new SemanticVersion("1.21.8"), null,
                Set.of(), runtime, runtime, ProtocolVersion.CURRENT);
        try (JavetPluginHost host = new JavetPluginHost(plugins, input, PlatformCapabilities.NONE, Duration.ZERO,
                Duration.ofSeconds(3), context -> Map.of(
                        "customBinding", arguments -> {
                            requests.add(List.copyOf(arguments));
                            if (requests.size() == FIRST_REQUEST_COUNT) {
                                return firstRegistration;
                            }
                            return CompletableFuture.completedFuture(PlatformOperationResult.owned(
                                    Map.of("status", "managed", "world", "failed"), closes::incrementAndGet));
                        },
                        "record", arguments -> {
                            events.add(String.valueOf(arguments.getFirst()));
                            return true;
                        }), System.getLogger(getClass().getName()))) {
            var startup = host.startAsync(Duration.ofSeconds(30));
            await(() -> !requests.isEmpty());

            assertFalse(startup.toCompletableFuture().isDone());
            assertEquals(List.of(Map.of("world", "spawn")), requests.getFirst());
            firstRegistration.complete(PlatformOperationResult.owned(
                    Map.of("status", "managed", "world", "spawn"), closes::incrementAndGet));
            startup.toCompletableFuture().join();

            assertEquals(List.of("managed:spawn"), events);
            assertEquals(1, host.snapshots().getFirst().resources().size());
            Files.writeString(managed.resolve("index.js"), """
                    export async function load() {
                      await host.customBinding({world: 'failed'});
                    }
                    export function ready() { throw new Error('candidate failed'); }
                    """);

            assertThrows(CompletionException.class,
                    () -> host.reload(new PluginId("managed-binding")).toCompletableFuture().join());
            assertEquals(List.of(Map.of("world", "failed")), requests.get(1));
            assertEquals(1, closes.get());
            assertEquals(1, host.snapshots().getFirst().resources().size());

            host.disable(new PluginId("managed-binding")).toCompletableFuture().join();
            host.unload(new PluginId("managed-binding")).toCompletableFuture().join();
            assertEquals(2, closes.get());
        }
    }

    @Test
    void runsTwoPluginsServicesReloadRollbackAndGenerationDisposal() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        Path provider = plugin("provider",
                "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}", """
                const entrypoint = Object.freeze({
                  enable(context) {
                    host.record('provider-load');
                    host.record('identity-' + context.plugin);
                    host.record('platform-' + context.platform);
                    host.record('compiler-' + context.metadata.version);
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
            assertTrue(events.containsAll(List.of(
                    "provider-load", "identity-provider", "platform-paper", "compiler-test", "service-42")));
            assertEquals(2, stagingEntries());

            Files.writeString(provider.resolve("index.js"), """
                    export function load() { host.record('provider-v2'); }
                    export function importHotState(value) { host.record('state-' + value[0]); }
                    export function exportHotState() { return new Uint8Array([8]); }
                    export function unload() { host.record('provider-v2-unload'); }
                    """);
            host.reload(new PluginId("provider")).toCompletableFuture().join();
            assertEquals(2, host.runtimeCount());
            assertTrue(events.containsAll(List.of("provider-v2", "state-7", "provider-unload")));
            assertEquals(2, stagingEntries());

            Files.writeString(provider.resolve("index.js"), """
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
            Files.writeString(plugins.resolve("consumer/index.js"),
                    "export default Object.freeze({enable(){host.record('consumer-reinstalled')}});\n");
            host.install(plugins.resolve("consumer")).toCompletableFuture().join();
            assertEquals(2, host.runtimeCount());
            assertEquals(2, stagingEntries());
            assertTrue(events.contains("consumer-reinstalled"));
        }
        assertEquals(0, stagingEntries());
    }

    @Test
    void acceptsCompilerSourceMapMetadata() throws Exception {
        plugin("mapped", "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}",
                "export function enable() {}\n");
        try (JavetPluginHost host = host(new CopyOnWriteArrayList<>())) {
            host.start(Duration.ofSeconds(30));
            assertEquals(1, host.runtimeCount());
        }
    }

    @Test
    void acceptsCompilerGeneratedCallbackNames() throws Exception {
        plugin("compiled-callback", "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}",
                "host.registerCallback('compiled.src/plugin.ts#EconomyPlugin.pay', () => true);\n");
        try (JavetPluginHost host = host(new CopyOnWriteArrayList<>())) {
            host.start(Duration.ofSeconds(30));
            assertEquals(1, host.runtimeCount());
            assertEquals(List.of("compiled-callback:true"), host.pluginStatuses().stream()
                    .map(status -> status.pluginId().value() + ":" + status.active()).toList());
        }
    }

    @Test
    void rejectsCorruptSourceMapAtAdmissionAndRecoversWatchedCandidate() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        plugin("valid", "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}",
                "export function enable() { host.record('valid-ready'); }\n");
        Path corrupt = plugin("corrupt",
                "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}",
                "export function enable() { host.record('corrupt-recovered'); }\n");
        Files.writeString(corrupt.resolve("index.js.map"), "{\"version\":2}");

        try (JavetPluginHost host = host(events)) {
            host.start(Duration.ofMillis(50));
            assertEquals(1, host.runtimeCount());
            assertEquals(List.of("valid"), host.snapshots().stream()
                    .map(snapshot -> snapshot.pluginId().value()).toList());
            assertEquals(List.of("valid:true", "corrupt:false"), host.pluginStatuses().stream()
                    .map(status -> status.pluginId().value() + ":" + status.active()).toList());
            assertTrue(events.contains("valid-ready"));
            assertEquals(1, stagingEntries());

            Files.writeString(corrupt.resolve("index.js.map"),
                    "{\"version\":3,\"sources\":[\"src/plugin.ts\"],\"mappings\":\"AAAA\"}");
            await(() -> events.contains("corrupt-recovered") && host.pluginStatuses().stream()
                    .anyMatch(status -> "corrupt".equals(status.pluginId().value()) && status.active()));
            assertEquals(2, host.runtimeCount());
            assertEquals(List.of("corrupt:true", "valid:true"), host.pluginStatuses().stream()
                    .map(status -> status.pluginId().value() + ":" + status.active()).toList());
            assertEquals(2, stagingEntries());
        }
        assertEquals(0, stagingEntries());
    }

    @Test
    void isolatesBrokenJavaScriptDuringStartup() throws Exception {
        List<String> events = new CopyOnWriteArrayList<>();
        plugin("broken-script", "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}",
                "export {");
        plugin("working-script", "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}",
                "export function enable() { host.record('working-ready'); }\n");

        try (JavetPluginHost host = host(events)) {
            host.start(Duration.ofSeconds(30));
            assertEquals(List.of("working-script:true", "broken-script:false"), host.pluginStatuses().stream()
                    .map(status -> status.pluginId().value() + ":" + status.active()).toList());
            assertTrue(events.contains("working-ready"));
        }
    }

    private JavetPluginHost host(List<String> events) {
        SemanticVersion runtime = new SemanticVersion("0.1.0");
        CompatibilityInput input = new CompatibilityInput(PlatformKind.PAPER,
                new SemanticVersion("1.21.8"), new SemanticVersion("1.21.8"), null,
                Set.of(), runtime, runtime, ProtocolVersion.CURRENT);
        return new JavetPluginHost(plugins, input, PlatformCapabilities.NONE, Duration.ZERO,
                Duration.ofSeconds(3),
                context -> Map.of("record", arguments -> {
                    events.add(String.valueOf(arguments.getFirst()));
                    return true;
                }), System.getLogger(getClass().getName()));
    }

    private Path plugin(String name, String dependencies, String source) throws Exception {
        Path root = Files.createDirectory(plugins.resolve(name));
        Files.writeString(root.resolve("index.js"), source);
        Files.writeString(root.resolve("index.js.map"),
                "{\"version\":3,\"sources\":[\"src/plugin.ts\"],\"mappings\":\"AAAA\"}");
        String communication = switch (name) {
            case "provider" -> "{\"services\":[{\"id\":\"counter\",\"version\":\"1.0.0\","
                    + "\"componentId\":\"provider\",\"methods\":[\"increment\"]}],\"events\":[],\"consumers\":[]}";
            case "consumer" -> "{\"services\":[],\"events\":[],\"consumers\":[{"
                    + "\"id\":\"counter\",\"versionRange\":\"^1.0.0\","
                    + "\"dependentReload\":\"keep-running\"}]}";
            default -> "{\"services\":[],\"events\":[],\"consumers\":[]}";
        };
        String components = switch (name) {
            case "provider" -> "[{\"id\":\"provider\",\"kind\":\"service\",\"name\":\"Provider\","
                    + "\"file\":\"src/plugin.ts\",\"platform\":\"paper\",\"decorators\":[],"
                    + "\"constructor\":[],\"properties\":[],\"methods\":[{\"name\":\"increment\","
                    + "\"decorators\":[],\"parameters\":[],\"location\":{\"file\":\"src/plugin.ts\","
                    + "\"line\":1,\"column\":1}}],\"location\":{\"file\":\"src/plugin.ts\","
                    + "\"line\":1,\"column\":1}}]";
            default -> "[]";
        };
        Files.writeString(root.resolve("shamoo-plugin.json"), """
                {"name":"%s","displayName":"%s","version":"1.0.0",
                "shamoo":{"api":"^0.1.0","runtime":"^0.1.0","manifest":2},
                "platforms":{"paper":{"enabled":true,"minecraft":"1.21.x",
                "paperApi":"1.21.x","nms":false,"packets":false},"velocity":{"enabled":false}},
                "dependencies":%s,
                "node":{"builtins":[],"filesystem":{"read":[],"write":[]},"network":false,
                "workers":false,"childProcess":false,"nativeAddons":false},
                "reload":{"watch":true,"debounceMs":100,"preserveState":true},
                "compiler":{"version":"test","components":%s,"modules":[],"communication":%s}}
                """.formatted(name, name, dependencies, components, communication));
        return root;
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(5).toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.get());
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
