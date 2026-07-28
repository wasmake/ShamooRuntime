package dev.shamoo.runtime.bootstrap.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.PluginRuntimeContext;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.OptionalProxyTransport;
import dev.shamoo.runtime.core.ScriptCallback;
import dev.shamoo.runtime.javet.JavetPluginHost;
import dev.shamoo.runtime.javet.HostFunction;
import dev.shamoo.runtime.protocol.CompatibilityInput;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.RuntimeCapability;
import dev.shamoo.runtime.protocol.SemanticVersion;
import dev.shamoo.runtime.protocol.Version;
import dev.shamoo.runtime.protocol.VersionParser;
import dev.shamoo.runtime.platform.paper.GeneratedPaperEventRegistry;
import dev.shamoo.runtime.platform.paper.PaperCommandContextBridge;
import dev.shamoo.runtime.platform.paper.PaperEventBridge;
import dev.shamoo.runtime.platform.paper.PaperCommandBridge;
import dev.shamoo.runtime.platform.paper.PaperSchedulerBridge;
import dev.shamoo.runtime.platform.paper.PaperMessagingBridge;
import dev.shamoo.runtime.platform.paper.ManagedLobbyStore;
import dev.shamoo.runtime.platform.paper.PaperManagedLobbyBridge;
import dev.shamoo.runtime.platform.paper.PaperManagedLobbyCoordinator;
import dev.shamoo.runtime.platform.paper.PaperUiBridge;
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
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.event.EventPriority;

/** Paper entry point that owns the native runtime lifecycle. */
public final class ShamooPaperPlugin extends JavaPlugin {
    private static final PluginId RUNTIME_OWNER = new PluginId("shamooruntime");
    private static final String PLATFORM_ARGUMENT = "platform binding argument ";
    private static final String FOLIA_MARKER = "io.papermc.paper.threadedregions.RegionizedServer";
    private JavetPluginHost pluginHost;
    private final ResourceRegistry packetResources = new ResourceRegistry();
    private final ResourceRegistry platformResources = new ResourceRegistry();
    private GeneratedPaperEventRegistry eventRegistry;
    private PaperEventBridge eventBridge;
    private PaperCommandBridge commandBridge;
    private PaperCommandContextBridge commandContextBridge;
    private PaperUiBridge uiBridge;
    private PaperSchedulerBridge schedulerBridge;
    private PaperMessagingBridge messagingBridge;
    private OptionalProxyTransport proxyTransport;
    private PacketDispatcherHub packetDispatcher;
    private PaperNmsInjectionManager packetManager;
    private PaperManagedLobbyCoordinator managedLobbyCoordinator;
    private ManagedLobbyStore managedLobbyStore;

    @Override
    public void onEnable() {
        try {
            requireManagedLobbyPlatform(getConfig().getBoolean("managed-lobby.enabled", false),
                    isFolia(getServer().getName(), classPresent(FOLIA_MARKER, getClassLoader())));
            eventRegistry = GeneratedPaperEventRegistry.load(getClassLoader());
            eventBridge = new PaperEventBridge(this, platformResources);
            commandContextBridge = new PaperCommandContextBridge(this);
            commandBridge = new PaperCommandBridge(this, commandContextBridge);
            uiBridge = new PaperUiBridge(this, commandContextBridge);
            schedulerBridge = new PaperSchedulerBridge(this, platformResources);
            messagingBridge = new PaperMessagingBridge(this, platformResources);
            proxyTransport = platformResources.register(new OptionalProxyTransport(Duration.ofSeconds(3)));
            messagingBridge.registerProxyTransport(RUNTIME_OWNER, proxyTransport);
            enablePackets();
            enablePacketProcessProbe();
            managedLobbyCoordinator = new PaperManagedLobbyCoordinator(this);
            pluginHost = new JavetPluginHost(pluginDirectory(), compatibility(), platformCapabilities(),
                    Duration.ofMillis(getConfig().getLong("plugins.stability-millis", 200)),
                    Duration.ofMillis(getConfig().getLong("plugins.drain-timeout-millis", 5000)),
                    this::customBindings, System.getLogger(getClass().getName()));
            PaperRuntimeCommands.register(this, pluginHost::pluginStatuses);
            pluginHost.startAsync(Duration.ofMillis(getConfig().getLong("plugins.watch-debounce-millis", 500)))
                    .whenComplete((ignored, failure) -> startupCompleted(failure));
        } catch (IOException | IllegalStateException exception) {
            startupFailed(exception);
        }
    }

    private void startupCompleted(Throwable failure) {
        if (failure == null) {
            if (getLogger().isLoggable(Level.INFO)) {
                getLogger().info("ShamooRuntime initialized with protocol " + ProtocolVersion.CURRENT
                        + " and " + pluginHost.runtimeCount() + " isolated plugins"
                        + " and " + eventRegistry.size() + " generated Paper events");
            }
            return;
        }
        try {
            getServer().getGlobalRegionScheduler().run(this, ignored -> startupFailed(failure));
        } catch (RuntimeException schedulingFailure) {
            failure.addSuppressed(schedulingFailure);
            getLogger().log(Level.SEVERE, "Unable to schedule V8 startup failure handling", failure);
        }
    }

    private void startupFailed(Throwable failure) {
        getLogger().log(Level.SEVERE, "Unable to initialize ShamooRuntime", failure);
        getServer().getPluginManager().disablePlugin(this);
    }

    static boolean isFolia(String serverName, boolean markerPresent) {
        return markerPresent || "Folia".equalsIgnoreCase(serverName);
    }

    static void requireManagedLobbyPlatform(boolean enabled, boolean folia) {
        if (enabled && folia) {
            throw new IllegalStateException("managed-lobby supports standard Paper 1.21.8 only; "
                    + "disable managed-lobby.enabled before running ShamooRuntime on Folia");
        }
    }

    private static boolean classPresent(String name, ClassLoader classLoader) {
        try {
            Class.forName(name, false, classLoader);
            return true;
        } catch (ClassNotFoundException ignored) {
            return false;
        }
    }

    private java.nio.file.Path pluginDirectory() {
        String configured = getConfig().getString("plugins.directory", "plugins");
        java.nio.file.Path path = java.nio.file.Path.of(configured);
        return path.isAbsolute() ? path : getDataFolder().toPath().resolve(path);
    }

    @SuppressWarnings("PMD.CloseResource")
    private Map<String, HostFunction> customBindings(PluginRuntimeContext context) {
        if (!getConfig().getBoolean("managed-lobby.enabled", false)) {
            return Map.of();
        }
        PluginId owner = new PluginId(getConfig().getString("managed-lobby.owner", "shalobby"));
        if (!owner.equals(context.candidate().pluginId())) {
            return Map.of();
        }
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(this, context.generationId(),
                sharedManagedLobbyStore(owner), managedLobbyCoordinator,
                context.invocations(), getConfig().getInt("managed-lobby.maximum-pending-actions", 64));
        context.resources().register(owner, ResourceCategory.GENERIC,
                "Paper managed lobby generation " + context.generationId(), bridge);
        return Map.of("paperManagedLobby", new HostFunction() {
            @Override
            public Object invoke(List<Object> arguments) {
                return bridge.invoke(arguments);
            }

            @Override
            public Object invoke(List<Object> arguments, boolean admitted) {
                return bridge.invoke(arguments, admitted);
            }
        });
    }

    private synchronized ManagedLobbyStore sharedManagedLobbyStore(PluginId owner) {
        if (managedLobbyStore == null) {
            managedLobbyStore = new ManagedLobbyStore(managedLobbyDirectory(owner));
        }
        return managedLobbyStore;
    }

    private java.nio.file.Path managedLobbyDirectory(PluginId owner) {
        String configured = getConfig().getString("managed-lobby.data-directory", "data");
        java.nio.file.Path path = java.nio.file.Path.of(configured);
        return confinedManagedLobbyDirectory(path.isAbsolute() ? path : getDataFolder().toPath().resolve(path),
                pluginDirectory(), owner);
    }

    static java.nio.file.Path confinedManagedLobbyDirectory(java.nio.file.Path dataRoot,
            java.nio.file.Path pluginDirectory, PluginId owner) {
        java.nio.file.Path root;
        java.nio.file.Path pluginRoot;
        java.nio.file.Path result;
        try {
            root = ManagedLobbyStore.resolveExistingAncestors(dataRoot);
            pluginRoot = ManagedLobbyStore.resolveExistingAncestors(pluginDirectory);
            result = ManagedLobbyStore.resolveExistingAncestors(root.resolve(owner.value()));
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to resolve managed-lobby data confinement", exception);
        }
        if (result.startsWith(pluginRoot) || pluginRoot.startsWith(result)) {
            throw new IllegalArgumentException("managed lobby owner directory must not overlap plugins.directory");
        }
        if (result.toString().length() > ManagedLobbyStore.MAX_DIRECTORY_CHARS) {
            throw new IllegalArgumentException("managed lobby owner directory exceeds 512 characters");
        }
        return result;
    }

    private CompatibilityInput compatibility() {
        String rawVersion = org.bukkit.Bukkit.getBukkitVersion().split("-", 2)[0];
        String minecraftVersion = normalizeMinecraftVersion(rawVersion);
        Version version = VersionParser.parse(minecraftVersion);
        return new CompatibilityInput(PlatformKind.PAPER, version, version, null,
                Set.of(RuntimeCapability.NODE_BUILTINS, RuntimeCapability.FILESYSTEM_READ,
                        RuntimeCapability.FILESYSTEM_WRITE), runtimeVersion(), runtimeVersion(),
                ProtocolVersion.CURRENT);
    }

    /**
     * Temporarily adapts Paper's Bukkit artifact version to the version formats understood by compatibility parsing.
     */
    // TODO: Remove this workaround once compatibility uses the dedicated
    // Minecraft version instead of the Bukkit artifact version.
    private static String normalizeMinecraftVersion(String version) {
        Objects.requireNonNull(version, "version");
        int buildIndex = version.indexOf(".build.");
        return buildIndex >= 0 ? version.substring(0, buildIndex) : version;
    }

    private SemanticVersion runtimeVersion() {
        return VersionParser.parseSemantic(RuntimeBuildVersion.VERSION.split("-", 2)[0]);
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
                    return commandBridge.register(owner, metadata, string(arguments, 0), strings(arguments, 1),
                            argument(arguments, 2), typed(arguments, 3, ScriptCallback.class));
                }),
                Map.entry("paperCommandReply", (owner, metadata, arguments) -> commandContextBridge.reply(
                        string(arguments, 0), argument(arguments, 1))),
                Map.entry("paperCommandOpenInventory", (owner, metadata, arguments) -> uiBridge.openInventory(
                        owner, string(arguments, 0), argument(arguments, 1))),
                Map.entry("paperCommandGiveItem", (owner, metadata, arguments) -> uiBridge.giveItem(
                        owner, string(arguments, 0), argument(arguments, 1))),
                Map.entry("paperCommandFindPlayer", (owner, metadata, arguments) -> commandContextBridge.findPlayer(
                        string(arguments, 0), string(arguments, 1))),
                Map.entry("paperCommandMainHand", (owner, metadata, arguments) -> commandContextBridge.mainHand(
                        string(arguments, 0))),
                Map.entry("paperCommandTakeMainHand", (owner, metadata, arguments) -> commandContextBridge.takeMainHand(
                        string(arguments, 0), string(arguments, 1), integer(arguments, 2))),
                Map.entry("paperScheduleGlobal", (owner, metadata, arguments) -> {
                    ScriptCallback callback = typed(arguments, 0, ScriptCallback.class);
                    return schedulerBridge.runGlobal(owner, () -> {
                        try {
                            callback.invoke(List.of()).whenComplete((ignored, failure) -> callback.close());
                        } catch (RuntimeException | Error failure) {
                            callback.close();
                            throw failure;
                        }
                    });
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

    private static Object argument(java.util.List<Object> arguments, int index) {
        if (index >= arguments.size()) {
            throw new IllegalArgumentException(PLATFORM_ARGUMENT + index + " is required");
        }
        return arguments.get(index);
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
        if (uiBridge != null) {
            uiBridge.close();
        }
        if (commandContextBridge != null) {
            commandContextBridge.close();
        }
        if (managedLobbyCoordinator != null) {
            managedLobbyCoordinator.close();
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
