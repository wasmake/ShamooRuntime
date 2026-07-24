package dev.shamoo.runtime.bootstrap.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.plugin.annotation.DataDirectory;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.shamoo.runtime.javet.JavetPluginHost;
import dev.shamoo.runtime.protocol.CompatibilityInput;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.RuntimeCapability;
import dev.shamoo.runtime.protocol.SemanticVersion;
import dev.shamoo.runtime.platform.velocity.GeneratedVelocityEventRegistry;
import dev.shamoo.runtime.platform.velocity.VelocityEventBridge;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.ScriptCallback;
import dev.shamoo.runtime.platform.velocity.VelocityCommandBridge;
import dev.shamoo.runtime.platform.velocity.VelocitySchedulerBridge;
import dev.shamoo.runtime.platform.velocity.VelocityMessagingBridge;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.nio.file.Path;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/** Velocity entry point that owns the native runtime lifecycle. */
@Plugin(
    id = "shamooruntime",
    name = "ShamooRuntime",
    version = RuntimeBuildVersion.VERSION,
    description = "JavaScript runtime foundation for Velocity"
)
public final class ShamooVelocityPlugin {
    private final ProxyServer server;
    private final Path dataDirectory;
    private JavetPluginHost pluginHost;
    private final ResourceRegistry platformResources = new ResourceRegistry();
    private GeneratedVelocityEventRegistry eventRegistry;
    private VelocityEventBridge eventBridge;
    private VelocityCommandBridge commandBridge;
    private VelocitySchedulerBridge schedulerBridge;
    private VelocityMessagingBridge messagingBridge;

    @Inject
    public ShamooVelocityPlugin(ProxyServer server, @DataDirectory Path dataDirectory) {
        this.server = server;
        this.dataDirectory = dataDirectory;
    }

    @Subscribe
    @SuppressWarnings("PMD.UseProperClassLoader")
    public void onProxyInitialization(ProxyInitializeEvent event) throws IOException {
        eventRegistry = GeneratedVelocityEventRegistry.load(getClass().getClassLoader());
        eventBridge = new VelocityEventBridge(server, this, platformResources);
        commandBridge = new VelocityCommandBridge(server, platformResources);
        schedulerBridge = new VelocitySchedulerBridge(server, this, platformResources);
        messagingBridge = new VelocityMessagingBridge(server, platformResources);
        String configured = System.getProperty("shamoo.plugins.directory", "plugins");
        Path directory = Path.of(configured);
        if (!directory.isAbsolute()) {
            directory = dataDirectory.resolve(directory);
        }
        pluginHost = new JavetPluginHost(directory, compatibility(), platformCapabilities(),
                Duration.ofMillis(200), Duration.ofSeconds(5), Duration.ofSeconds(5),
                context -> Map.of(), System.getLogger(getClass().getName()));
        pluginHost.start(Duration.ofMillis(500));
        System.getLogger(getClass().getName()).log(
            System.Logger.Level.INFO, "ShamooRuntime initialized with protocol " + ProtocolVersion.CURRENT
                    + " and " + pluginHost.runtimeCount() + " isolated plugins"
                    + " and " + eventRegistry.size() + " generated Velocity events");
    }

    private CompatibilityInput compatibility() {
        String implementation = server.getVersion().getVersion();
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+\\.\\d+\\.\\d+)")
                .matcher(implementation);
        SemanticVersion velocity = new SemanticVersion(matcher.find() ? matcher.group(1) : "3.4.0");
        SemanticVersion runtime = new SemanticVersion("0.1.0");
        return new CompatibilityInput(PlatformKind.VELOCITY, null, null, velocity,
                Set.of(RuntimeCapability.NODE_BUILTINS, RuntimeCapability.FILESYSTEM_READ,
                        RuntimeCapability.FILESYSTEM_WRITE), runtime, runtime, ProtocolVersion.CURRENT);
    }

    private PlatformCapabilities platformCapabilities() {
        return new PlatformCapabilities("velocity", Map.of(
                "velocitySubscribeEvent", (owner, metadata, arguments) -> {
                    return eventBridge.subscribe(owner, eventRegistry, string(arguments, 0),
                            number(arguments, 1).shortValue(),
                            liveEvent -> typed(arguments, 2, ScriptCallback.class).invoke(java.util.List.of(
                                    Map.of("type", liveEvent.getClass().getName()))).thenApply(ignored -> null));
                },
                "velocityRegisterCommand", (owner, metadata, arguments) -> {
                    var commandMeta = server.getCommandManager().metaBuilder(string(arguments, 0))
                            .aliases(strings(arguments, 1).toArray(String[]::new)).build();
                    return commandBridge.registerSimple(owner, commandMeta,
                            invocation -> typed(arguments, 2, ScriptCallback.class).invoke(java.util.List.of(Map.of(
                                    "source", invocation.source().toString(),
                                    "arguments", java.util.List.of(invocation.arguments())))));
                },
                "velocitySchedule", (owner, metadata, arguments) -> {
                    return schedulerBridge.runLater(owner, Duration.ofMillis(number(arguments, 0).longValue()),
                            () -> typed(arguments, 1, ScriptCallback.class).invoke(java.util.List.of()));
                },
                "velocityRegisterMessaging", (owner, metadata, arguments) -> {
                    return messagingBridge.register(owner, MinecraftChannelIdentifier.from(string(arguments, 0)));
                },
                "velocityRegisterProxyEndpoint", (owner, metadata, arguments) -> {
                    return messagingBridge.registerProxyEndpoint(owner, this,
                            MinecraftChannelIdentifier.from("shamoo:runtime_v1"),
                            request -> typed(arguments, 0, ScriptCallback.class).invoke(java.util.List.of(request))
                                    .thenApply(value -> {
                                        if (!(value instanceof byte[] bytes)) {
                                            throw new IllegalArgumentException("proxy callback must return bytes");
                                        }
                                        return bytes.clone();
                                    }));
                }));
    }

    private static String string(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, String.class);
    }

    private static Number number(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, Number.class);
    }

    private static java.util.List<String> strings(java.util.List<Object> arguments, int index) {
        Object value = arguments.get(index);
        if (!(value instanceof java.util.List<?> values)
                || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException("platform binding argument " + index + " must be a string array");
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static <T> T typed(java.util.List<Object> arguments, int index, Class<T> type) {
        if (index >= arguments.size() || !type.isInstance(arguments.get(index))) {
            throw new IllegalArgumentException("platform binding argument " + index + " must be " + type.getName());
        }
        return type.cast(arguments.get(index));
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (pluginHost != null) {
            pluginHost.close();
        }
        try {
            platformResources.closeAll();
        } catch (Exception exception) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.WARNING,
                    "Unable to close Velocity platform resources", exception);
        }
    }

    public VelocityEventBridge.Subscription<?> subscribeEvent(PluginId owner, String generatedName, short order,
            VelocityEventBridge.AsyncEventDispatcher<Object> dispatcher) {
        if (eventBridge == null) {
            throw new IllegalStateException("Velocity event adapter is not initialized");
        }
        return eventBridge.subscribe(owner, eventRegistry, generatedName, order, dispatcher);
    }

    public JavetPluginHost runtimeHost() {
        if (pluginHost == null) {
            throw new IllegalStateException("Script plugin host is not initialized");
        }
        return pluginHost;
    }
}
