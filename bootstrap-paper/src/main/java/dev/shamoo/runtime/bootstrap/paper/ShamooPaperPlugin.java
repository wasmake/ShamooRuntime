package dev.shamoo.runtime.bootstrap.paper;

import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.javet.JavetScriptRuntime;
import dev.shamoo.runtime.platform.paper.PaperRuntimeHost;
import dev.shamoo.runtime.platform.paper.GeneratedPaperEventRegistry;
import dev.shamoo.runtime.platform.paper.PaperEventBridge;
import dev.shamoo.runtime.platform.paper.PaperCommandBridge;
import dev.shamoo.runtime.platform.paper.PaperSchedulerBridge;
import dev.shamoo.runtime.platform.paper.PaperMessagingBridge;
import dev.shamoo.runtime.platform.paper.nms.GeneratedPacketRegistry;
import dev.shamoo.runtime.platform.paper.nms.PaperNmsInjectionManager;
import dev.shamoo.runtime.platform.paper.packet.PacketAccessPolicy;
import dev.shamoo.runtime.platform.paper.packet.PaperPacketBridge;
import dev.shamoo.runtime.platform.paper.packet.PacketDispatcherHub;
import java.io.IOException;
import java.time.Duration;
import java.util.Set;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventPriority;

/** Paper entry point that owns the native runtime lifecycle. */
public final class ShamooPaperPlugin extends JavaPlugin {
    private ScriptRuntime runtime;
    private final ResourceRegistry packetResources = new ResourceRegistry();
    private final ResourceRegistry platformResources = new ResourceRegistry();
    private GeneratedPaperEventRegistry eventRegistry;
    private PaperEventBridge eventBridge;
    private PaperCommandBridge commandBridge;
    private PaperSchedulerBridge schedulerBridge;
    private PaperMessagingBridge messagingBridge;
    private PacketDispatcherHub packetDispatcher;
    private PaperNmsInjectionManager packetManager;

    @Override
    public void onEnable() {
        try {
            eventRegistry = GeneratedPaperEventRegistry.load(getClassLoader());
            eventBridge = new PaperEventBridge(this, platformResources);
            commandBridge = new PaperCommandBridge(this, platformResources);
            schedulerBridge = new PaperSchedulerBridge(this, platformResources);
            messagingBridge = new PaperMessagingBridge(this, platformResources);
            enablePackets();
            enablePacketProcessProbe();
            PluginId owner = new PluginId("shamooruntime");
            runtime = new JavetScriptRuntime(new PaperRuntimeHost(this), owner, platformCapabilities());
            if (getLogger().isLoggable(Level.INFO)) {
                getLogger().info("ShamooRuntime initialized with protocol " + runtime.protocolVersion()
                        + " and " + eventRegistry.size() + " generated Paper events");
            }
        } catch (RuntimeInitializationException | IOException | IllegalStateException exception) {
            if (getLogger().isLoggable(Level.SEVERE)) {
                getLogger().severe("Unable to initialize the V8 runtime: " + exception.getMessage());
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private PlatformCapabilities platformCapabilities() {
        return new PlatformCapabilities(Map.of(
                "paperSubscribeEvent", (owner, metadata, arguments) -> {
                    eventBridge.subscribe(owner, eventRegistry, string(arguments, 0),
                            EventPriority.valueOf(string(arguments, 1)), bool(arguments, 2),
                            typed(arguments, 3, PaperEventBridge.SynchronousEventDispatcher.class));
                    return true;
                },
                "paperRegisterCommand", (owner, metadata, arguments) -> {
                    commandBridge.register(owner, string(arguments, 0), strings(arguments, 1),
                            typed(arguments, 2, PaperCommandBridge.CommandDispatcher.class));
                    return true;
                },
                "paperScheduleGlobal", (owner, metadata, arguments) -> {
                    schedulerBridge.runGlobal(owner, typed(arguments, 0, Runnable.class));
                    return true;
                },
                "paperRegisterMessaging", (owner, metadata, arguments) -> {
                    messagingBridge.register(owner, string(arguments, 0),
                            typed(arguments, 1, org.bukkit.plugin.messaging.PluginMessageListener.class));
                    return true;
                },
                "paperSubscribePacket", (owner, metadata, arguments) -> {
                    subscribePackets(owner, typed(arguments, 0, PaperPacketBridge.PacketDispatcher.class));
                    return true;
                }));
    }

    private static String string(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, String.class);
    }

    private static boolean bool(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, Boolean.class);
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

    @Override
    public void onDisable() {
        if (packetManager != null) {
            packetManager.close();
        }
        try {
            platformResources.closeAll();
            packetResources.closeAll();
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "Unable to close all platform resources", exception);
        }
        if (runtime != null) {
            runtime.close();
        }
    }

    private void enablePackets() throws IOException {
        saveDefaultConfig();
        if (!getConfig().getBoolean("packets.enabled", false)) {
            return;
        }
        PluginId owner = new PluginId("shamooruntime");
        Set<PluginId> allowed = getConfig().getStringList("packets.allowed-plugins").stream()
                .map(PluginId::new).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PluginId> infrastructure = new java.util.HashSet<>(allowed);
        infrastructure.add(owner);
        PaperPacketBridge bridge = new PaperPacketBridge(new PacketAccessPolicy(true, infrastructure),
                GeneratedPacketRegistry.load(getClassLoader()), packetResources,
                Duration.ofMillis(getConfig().getLong("packets.timeout-millis", 50)),
                getConfig().getInt("packets.maximum-pending", 256));
        packetDispatcher = new PacketDispatcherHub(new PacketAccessPolicy(true, allowed), packetResources);
        packetManager = new PaperNmsInjectionManager(this, owner, bridge, packetDispatcher);
        packetManager.start();
    }

    private void enablePacketProcessProbe() {
        if (!getConfig().getBoolean("packets.process-smoke", false)) {
            return;
        }
        PluginId owner = new PluginId("shamooruntime");
        AtomicInteger intercepted = new AtomicInteger();
        subscribePackets(owner, packet -> {
            if (packet.direction() == PaperPacketBridge.Direction.OUTBOUND
                    && packet.phase() == dev.shamoo.runtime.platform.paper.packet.PacketRegistry.Phase.STATUS) {
                int count = intercepted.incrementAndGet();
                if (getLogger().isLoggable(Level.INFO)) {
                    getLogger().info("SHAMOO_PACKET_SMOKE intercepted=" + count + " action=cancel");
                }
                return CompletableFuture.completedFuture(PaperPacketBridge.Decision.cancel());
            }
            return CompletableFuture.completedFuture(PaperPacketBridge.Decision.pass());
        });
    }

    public PaperEventBridge.Subscription subscribeEvent(PluginId owner, String generatedName, EventPriority priority,
            boolean receiveCancelled, PaperEventBridge.SynchronousEventDispatcher dispatcher) {
        if (eventBridge == null) {
            throw new IllegalStateException("Paper event adapter is not initialized");
        }
        return eventBridge.subscribe(owner, eventRegistry, generatedName, priority, receiveCancelled, dispatcher);
    }

    public AutoCloseable subscribePackets(PluginId owner, PaperPacketBridge.PacketDispatcher dispatcher) {
        if (packetDispatcher == null) {
            throw new IllegalStateException("Paper packet interception is disabled");
        }
        return packetDispatcher.subscribe(owner, dispatcher);
    }
}
