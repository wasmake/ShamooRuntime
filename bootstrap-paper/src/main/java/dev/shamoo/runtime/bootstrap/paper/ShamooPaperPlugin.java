package dev.shamoo.runtime.bootstrap.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.OptionalProxyTransport;
import dev.shamoo.runtime.core.ScriptCallback;
import dev.shamoo.runtime.javet.JavetPluginHost;
import dev.shamoo.runtime.protocol.CompatibilityInput;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.RuntimeCapability;
import dev.shamoo.runtime.protocol.SemanticVersion;
import dev.shamoo.runtime.platform.paper.GeneratedPaperEventRegistry;
import dev.shamoo.runtime.platform.paper.PaperCommandContextBridge;
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
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventPriority;

/** Paper entry point that owns the native runtime lifecycle. */
public final class ShamooPaperPlugin extends JavaPlugin {
    private static final PluginId RUNTIME_OWNER = new PluginId("shamooruntime");
    private static final String PLATFORM_ARGUMENT = "platform binding argument ";
    private JavetPluginHost pluginHost;
    private final ResourceRegistry packetResources = new ResourceRegistry();
    private final ResourceRegistry platformResources = new ResourceRegistry();
    private GeneratedPaperEventRegistry eventRegistry;
    private PaperEventBridge eventBridge;
    private PaperCommandBridge commandBridge;
    private PaperCommandContextBridge commandContextBridge;
    private PaperSchedulerBridge schedulerBridge;
    private PaperMessagingBridge messagingBridge;
    private OptionalProxyTransport proxyTransport;
    private PacketDispatcherHub packetDispatcher;
    private PaperNmsInjectionManager packetManager;

    @Override
    public void onEnable() {
        try {
            eventRegistry = GeneratedPaperEventRegistry.load(getClassLoader());
            eventBridge = new PaperEventBridge(this, platformResources);
            commandBridge = new PaperCommandBridge(this, platformResources);
            commandContextBridge = new PaperCommandContextBridge(getServer());
            schedulerBridge = new PaperSchedulerBridge(this, platformResources);
            messagingBridge = new PaperMessagingBridge(this, platformResources);
            proxyTransport = platformResources.register(new OptionalProxyTransport(Duration.ofSeconds(3)));
            messagingBridge.registerProxyTransport(RUNTIME_OWNER, proxyTransport);
            enablePackets();
            enablePacketProcessProbe();
            pluginHost = new JavetPluginHost(pluginDirectory(), compatibility(), platformCapabilities(),
                    Duration.ofMillis(getConfig().getLong("plugins.stability-millis", 200)),
                    Duration.ofMillis(getConfig().getLong("plugins.hook-timeout-millis", 5000)),
                    Duration.ofMillis(getConfig().getLong("plugins.drain-timeout-millis", 5000)),
                    context -> Map.of(), System.getLogger(getClass().getName()));
            pluginHost.start(Duration.ofMillis(getConfig().getLong("plugins.watch-debounce-millis", 500)));
            if (getLogger().isLoggable(Level.INFO)) {
                getLogger().info("ShamooRuntime initialized with protocol " + ProtocolVersion.CURRENT
                        + " and " + pluginHost.runtimeCount() + " isolated plugins"
                        + " and " + eventRegistry.size() + " generated Paper events");
            }
        } catch (IOException | IllegalStateException exception) {
            if (getLogger().isLoggable(Level.SEVERE)) {
                getLogger().severe("Unable to initialize the V8 runtime: " + exception.getMessage());
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    private java.nio.file.Path pluginDirectory() {
        String configured = getConfig().getString("plugins.directory", "plugins");
        java.nio.file.Path path = java.nio.file.Path.of(configured);
        return path.isAbsolute() ? path : getDataFolder().toPath().resolve(path);
    }

    private CompatibilityInput compatibility() {
        SemanticVersion minecraft = new SemanticVersion(org.bukkit.Bukkit.getMinecraftVersion());
        String api = org.bukkit.Bukkit.getBukkitVersion().split("-", 2)[0];
        return new CompatibilityInput(PlatformKind.PAPER, minecraft, new SemanticVersion(api), null,
                Set.of(RuntimeCapability.NODE_BUILTINS, RuntimeCapability.FILESYSTEM_READ,
                        RuntimeCapability.FILESYSTEM_WRITE), runtimeVersion(), runtimeVersion(),
                ProtocolVersion.CURRENT);
    }

    private SemanticVersion runtimeVersion() {
        String version = getPluginMeta().getVersion();
        return new SemanticVersion(version.endsWith("-SNAPSHOT")
                ? version.substring(0, version.length() - "-SNAPSHOT".length()) : version);
    }

    private PlatformCapabilities platformCapabilities() {
        return new PlatformCapabilities("paper", Map.ofEntries(
                Map.entry("paperSubscribeEvent", (owner, metadata, arguments) -> {
                    return eventBridge.subscribe(owner, eventRegistry, string(arguments, 0),
                            EventPriority.valueOf(string(arguments, 1)), bool(arguments, 2),
                            event -> typed(arguments, 3, ScriptCallback.class).invoke(List.of(Map.of(
                                    "type", event.getEventName(), "asynchronous", event.isAsynchronous())))
                                    .toCompletableFuture().join());
                }),
                Map.entry("paperRegisterCommand", (owner, metadata, arguments) -> {
                    return commandBridge.register(owner, string(arguments, 0), strings(arguments, 1),
                            (sender, alias, values) -> commandContextBridge.execute(sender, alias, values,
                                    context -> typed(arguments, 2, ScriptCallback.class).invoke(List.of(context))));
                }),
                Map.entry("paperCommandReply", (owner, metadata, arguments) -> commandContextBridge.reply(
                        string(arguments, 0), string(arguments, 1))),
                Map.entry("paperCommandFindPlayer", (owner, metadata, arguments) -> commandContextBridge.findPlayer(
                        string(arguments, 0), string(arguments, 1))),
                Map.entry("paperCommandMainHand", (owner, metadata, arguments) -> commandContextBridge.mainHand(
                        string(arguments, 0))),
                Map.entry("paperCommandTakeMainHand", (owner, metadata, arguments) -> commandContextBridge.takeMainHand(
                        string(arguments, 0), string(arguments, 1), integer(arguments, 2))),
                Map.entry("paperScheduleGlobal", (owner, metadata, arguments) -> {
                    ScriptCallback callback = typed(arguments, 0, ScriptCallback.class);
                    return schedulerBridge.runGlobal(owner, () -> callback.invoke(List.of()));
                }),
                Map.entry("paperRegisterMessaging", (owner, metadata, arguments) -> {
                    return messagingBridge.register(owner, string(arguments, 0),
                            (channel, player, payload) -> typed(arguments, 1, ScriptCallback.class).invoke(List.of(
                                    Map.of("channel", channel, "playerId", player.getUniqueId().toString(),
                                            "payload", payload.clone()))));
                }),
                Map.entry("paperProxyCarrier", (owner, metadata, arguments) ->
                        messagingBridge.selectCarrier(proxyTransport)),
                Map.entry("paperProxyRequest", (owner, metadata, arguments) -> proxyTransport.request(
                        typed(arguments, 0, byte[].class))),
                Map.entry("paperSubscribePacket", (owner, metadata, arguments) -> {
                    ScriptCallback callback = typed(arguments, 0, ScriptCallback.class);
                    return subscribePackets(owner, packet -> callback.invoke(List.of(Map.of(
                            "direction", packet.direction().name(), "phase", packet.phase().name(),
                            "protocolDirection", packet.protocolDirection().name(),
                            "protocolId", packet.protocolId(),
                            "packetType", packet.packet().descriptor().id()))).thenApply(value -> {
                                if (!(value instanceof Map<?, ?> decision)) {
                                    throw new IllegalArgumentException("packet callback must return a decision object");
                                }
                                return Boolean.TRUE.equals(decision.get("cancelled"))
                                        ? PaperPacketBridge.Decision.cancel() : PaperPacketBridge.Decision.pass();
                            }));
                })));
    }

    private static String string(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, String.class);
    }

    private static boolean bool(java.util.List<Object> arguments, int index) {
        return typed(arguments, index, Boolean.class);
    }

    private static int integer(java.util.List<Object> arguments, int index) {
        Object value = index < arguments.size() ? arguments.get(index) : null;
        if (!(value instanceof Number number)) {
            throw new IllegalArgumentException(PLATFORM_ARGUMENT + index + " must be an integer");
        }
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE || number.doubleValue() != result) {
            throw new IllegalArgumentException(PLATFORM_ARGUMENT + index + " must be an integer");
        }
        return (int) result;
    }

    private static java.util.List<String> strings(java.util.List<Object> arguments, int index) {
        Object value = arguments.get(index);
        if (!(value instanceof java.util.List<?> values)
                || values.stream().anyMatch(item -> !(item instanceof String))) {
            throw new IllegalArgumentException(PLATFORM_ARGUMENT + index + " must be a string array");
        }
        return values.stream().map(String.class::cast).toList();
    }

    private static <T> T typed(java.util.List<Object> arguments, int index, Class<T> type) {
        if (index >= arguments.size() || !type.isInstance(arguments.get(index))) {
            throw new IllegalArgumentException(PLATFORM_ARGUMENT + index + " must be " + type.getName());
        }
        return type.cast(arguments.get(index));
    }

    @Override
    public void onDisable() {
        if (pluginHost != null) {
            try {
                pluginHost.close();
            } catch (RuntimeException exception) {
                getLogger().log(Level.WARNING, "Unable to close all script plugin runtimes", exception);
            }
        }
        if (packetManager != null) {
            packetManager.close();
        }
        try {
            platformResources.closeAll();
            packetResources.closeAll();
        } catch (Exception exception) {
            getLogger().log(Level.WARNING, "Unable to close all platform resources", exception);
        }
    }

    private void enablePackets() throws IOException {
        saveDefaultConfig();
        if (!getConfig().getBoolean("packets.enabled", false)) {
            return;
        }
        Set<PluginId> allowed = getConfig().getStringList("packets.allowed-plugins").stream()
                .map(PluginId::new).collect(java.util.stream.Collectors.toUnmodifiableSet());
        Set<PluginId> infrastructure = new java.util.HashSet<>(allowed);
        infrastructure.add(RUNTIME_OWNER);
        PaperPacketBridge bridge = new PaperPacketBridge(new PacketAccessPolicy(true, infrastructure),
                GeneratedPacketRegistry.load(getClassLoader()), packetResources,
                Duration.ofMillis(getConfig().getLong("packets.timeout-millis", 50)),
                getConfig().getInt("packets.maximum-pending", 256));
        packetDispatcher = new PacketDispatcherHub(new PacketAccessPolicy(true, allowed), packetResources);
        packetManager = new PaperNmsInjectionManager(this, RUNTIME_OWNER, bridge, packetDispatcher);
        packetManager.start();
    }

    private void enablePacketProcessProbe() {
        if (!getConfig().getBoolean("packets.process-smoke", false)) {
            return;
        }
        AtomicInteger intercepted = new AtomicInteger();
        subscribePackets(RUNTIME_OWNER, packet -> {
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

    public JavetPluginHost runtimeHost() {
        if (pluginHost == null) {
            throw new IllegalStateException("Script plugin host is not initialized");
        }
        return pluginHost;
    }
}
