package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.HotStatePluginRuntime;
import dev.shamoo.runtime.core.PluginRuntimeContext;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.PluginArtifactProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Production lifecycle adapter for a plugin entrypoint in one confined Javet isolate. */
public final class JavetPluginRuntime implements HotStatePluginRuntime {
    private static final int MAX_HOT_STATE_BYTES = 1_048_576;
    private static final String LIFECYCLE_GLOBAL = "__shamooPluginLifecycle";
    private final ShamooNodeRuntime runtime;
    private final String source;
    private final PlatformKind platform;
    private final String pluginName;
    private final Path pluginRoot;
    private final SourceMapV3.ValidatedSourceMap sourceMap;
    private boolean initialized;

    public JavetPluginRuntime(PluginRuntimeContext context, ShamooNodeRuntime runtime, PlatformKind platform) {
        this(context, runtime, platform, null);
    }

    JavetPluginRuntime(PluginRuntimeContext context, ShamooNodeRuntime runtime, PlatformKind platform,
            SourceMapV3.ValidatedSourceMap sourceMap) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        Objects.requireNonNull(context, "context");
        Objects.requireNonNull(platform, "platform");
        this.platform = platform;
        pluginName = context.candidate().descriptor().name();
        pluginRoot = context.candidate().root();
        this.sourceMap = sourceMap;
        Path path = pluginRoot.resolve(PluginArtifactProtocol.MODULE_FILE).normalize();
        if (!path.startsWith(pluginRoot) || !Files.isRegularFile(path)) {
            throw new IllegalArgumentException("plugin module is not a regular staged file: "
                    + PluginArtifactProtocol.MODULE_FILE);
        }
        try {
            source = Files.readString(path, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read plugin module: "
                    + PluginArtifactProtocol.MODULE_FILE, exception);
        }
    }

    @Override
    public CompletionStage<Void> load() {
        if (initialized) {
            return hook("load");
        }
        initialized = true;
        CompletionStage<Void> maps = sourceMap == null
                ? SourceMapV3.registerAdjacent(runtime, pluginRoot) : sourceMap.register(runtime);
        return maps.thenCompose(ignored -> runtime.registerModule(
                        PluginArtifactProtocol.MODULE_FILE, source, ModuleKind.ESM))
                .thenCompose(ignored -> executeEsmAdapter())
                .thenCompose(ignored -> hook("load"));
    }

    @Override public CompletionStage<Void> enable() { return hook("enable"); }
    @Override public CompletionStage<Void> ready() { return hook("ready"); }
    @Override public CompletionStage<Void> drain() { return hook("drain"); }
    @Override public CompletionStage<Void> disable() { return hook("disable"); }
    @Override public CompletionStage<Void> unload() { return hook("unload"); }

    @Override
    public CompletionStage<byte[]> exportHotState() {
        return runtime.evaluate("(async()=>{const f=globalThis." + LIFECYCLE_GLOBAL
                + "?.exportHotState;if(typeof f!=='function')return [];const v=await f();"
                + "if(v==null)return [];if(v instanceof Uint8Array)return Array.from(v);"
                + "if(typeof v==='string')return Array.from(new TextEncoder().encode(v));"
                + "throw new TypeError('exportHotState must return a string or Uint8Array')})()",
                        PluginArtifactProtocol.MODULE_FILE)
                .thenApply(JavetPluginRuntime::decodeState);
    }

    @Override
    public CompletionStage<Void> importHotState(byte[] state) {
        byte[] snapshot = Objects.requireNonNull(state, "state").clone();
        if (snapshot.length > MAX_HOT_STATE_BYTES) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("hot state exceeds 1 MiB"));
        }
        String values = java.util.stream.IntStream.range(0, snapshot.length)
                .map(index -> Byte.toUnsignedInt(snapshot[index]))
                .mapToObj(Integer::toString).collect(java.util.stream.Collectors.joining(","));
        return runtime.evaluate("(async()=>{const f=globalThis." + LIFECYCLE_GLOBAL
                + "?.importHotState;if(typeof f==='function')await f(new Uint8Array(["
                + values + "]))})()", PluginArtifactProtocol.MODULE_FILE).thenApply(ignored -> null);
    }

    private CompletionStage<Void> hook(String name) {
        String actual = lifecycleName(name);
        return runtime.evaluate("(async()=>{const f=globalThis." + LIFECYCLE_GLOBAL + "?." + name
                + ";const g=globalThis." + LIFECYCLE_GLOBAL + "?." + actual
                + ";const h=typeof f==='function'?f:g;if(typeof h==='function')await h(Object.freeze({"
                + "plugin:" + quote(pluginName) + ",platform:"
                + quote(platform.name().toLowerCase(java.util.Locale.ROOT))
                + ",metadata:Object.freeze(host.shamooMetadata())}))})()",
                        PluginArtifactProtocol.MODULE_FILE).thenApply(ignored -> null);
    }

    private CompletionStage<Void> executeEsmAdapter() {
        String adapterName = ".shamoo-entrypoint-adapter.mjs";
        final String adapter = adapterName;
        String sourceCode = "import * as entrypoint from './" + PluginArtifactProtocol.MODULE_FILE + "';\n"
                + "const value=entrypoint.default&&typeof entrypoint.default==='object'"
                + "?entrypoint.default:entrypoint;globalThis." + LIFECYCLE_GLOBAL + "=value;\n";
        return runtime.registerModule(adapter, sourceCode, ModuleKind.ESM)
                .thenCompose(ignored -> runtime.executeModule(adapter)).thenApply(ignored -> null);
    }

    private String lifecycleName(String name) {
        if (platform == PlatformKind.PAPER) {
            return name;
        }
        return switch (name) {
            case "enable" -> "start";
            case "disable" -> "stop";
            default -> name;
        };
    }

    private static String quote(String value) {
        return "'" + value.replace("\\", "\\\\").replace("'", "\\'") + "'";
    }

    private static byte[] decodeState(Object value) {
        if (!(value instanceof List<?> values) || values.size() > MAX_HOT_STATE_BYTES) {
            throw new IllegalArgumentException("hot state exceeds 1 MiB");
        }
        byte[] state = new byte[values.size()];
        for (int index = 0; index < state.length; index++) {
            Object item = values.get(index);
            if (!(item instanceof Number number) || number.intValue() < 0 || number.intValue() > 255) {
                throw new IllegalArgumentException("hot state must contain only bytes");
            }
            state[index] = (byte) number.intValue();
        }
        return state;
    }

}
