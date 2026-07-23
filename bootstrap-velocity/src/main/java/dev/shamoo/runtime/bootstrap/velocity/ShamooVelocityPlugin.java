package dev.shamoo.runtime.bootstrap.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.javet.JavetScriptRuntime;
import dev.shamoo.runtime.platform.velocity.VelocityRuntimeHost;
import dev.shamoo.runtime.platform.velocity.GeneratedVelocityEventRegistry;
import dev.shamoo.runtime.platform.velocity.VelocityEventBridge;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.platform.velocity.VelocityCommandBridge;
import dev.shamoo.runtime.platform.velocity.VelocitySchedulerBridge;
import dev.shamoo.runtime.platform.velocity.VelocityMessagingBridge;
import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;

/** Velocity entry point that owns the native runtime lifecycle. */
@Plugin(
    id = "shamooruntime",
    name = "ShamooRuntime",
    version = "0.1.0-SNAPSHOT",
    description = "JavaScript runtime foundation for Velocity"
)
public final class ShamooVelocityPlugin {
    private final ProxyServer server;
    private ScriptRuntime runtime;
    private final ResourceRegistry platformResources = new ResourceRegistry();
    private GeneratedVelocityEventRegistry eventRegistry;
    private VelocityEventBridge eventBridge;
    private VelocityCommandBridge commandBridge;
    private VelocitySchedulerBridge schedulerBridge;
    private VelocityMessagingBridge messagingBridge;

    @Inject
    public ShamooVelocityPlugin(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    @SuppressWarnings("PMD.UseProperClassLoader")
    public void onProxyInitialization(ProxyInitializeEvent event) throws RuntimeInitializationException, IOException {
        eventRegistry = GeneratedVelocityEventRegistry.load(getClass().getClassLoader());
        eventBridge = new VelocityEventBridge(server, this, platformResources);
        commandBridge = new VelocityCommandBridge(server, platformResources);
        schedulerBridge = new VelocitySchedulerBridge(server, this, platformResources);
        messagingBridge = new VelocityMessagingBridge(server, platformResources);
        runtime = new JavetScriptRuntime(new VelocityRuntimeHost(server, this), new PluginId("shamooruntime"),
                platformCapabilities());
        System.getLogger(getClass().getName()).log(
            System.Logger.Level.INFO, "ShamooRuntime initialized with protocol " + runtime.protocolVersion()
                    + " and " + eventRegistry.size() + " generated Velocity events");
    }

    private PlatformCapabilities platformCapabilities() {
        return new PlatformCapabilities(Map.of(
                "velocitySubscribeEvent", (owner, metadata, arguments) -> {
                    eventBridge.subscribe(owner, eventRegistry, string(arguments, 0),
                            number(arguments, 1).shortValue(),
                            eventDispatcher(arguments, 2));
                    return true;
                },
                "velocityRegisterCommand", (owner, metadata, arguments) -> {
                    var commandMeta = server.getCommandManager().metaBuilder(string(arguments, 0))
                            .aliases(strings(arguments, 1).toArray(String[]::new)).build();
                    commandBridge.registerSimple(owner, commandMeta,
                            typed(arguments, 2, VelocityCommandBridge.SimpleDispatcher.class));
                    return true;
                },
                "velocitySchedule", (owner, metadata, arguments) -> {
                    schedulerBridge.runLater(owner, Duration.ofMillis(number(arguments, 0).longValue()),
                            typed(arguments, 1, Runnable.class));
                    return true;
                },
                "velocityRegisterMessaging", (owner, metadata, arguments) -> {
                    messagingBridge.register(owner, MinecraftChannelIdentifier.from(string(arguments, 0)));
                    return true;
                }));
    }

    private static String string(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, String.class);
    }

    private static Number number(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, Number.class);
    }

    @SuppressWarnings("unchecked")
    private static VelocityEventBridge.AsyncEventDispatcher<Object> eventDispatcher(
            java.util.List<Object> arguments, int index) {
        Object value = arguments.get(index);
        if (!(value instanceof VelocityEventBridge.AsyncEventDispatcher<?> dispatcher)) {
            throw new IllegalArgumentException("platform binding argument " + index + " must be an event dispatcher");
        }
        return (VelocityEventBridge.AsyncEventDispatcher<Object>) dispatcher;
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
        if (runtime != null) {
            runtime.close();
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
}
