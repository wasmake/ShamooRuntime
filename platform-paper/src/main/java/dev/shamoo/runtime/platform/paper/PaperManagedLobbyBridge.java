package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.InvocationController;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.Particle;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.SoundCategory;
import org.bukkit.World;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.Attribute;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.entity.Projectile;
import org.bukkit.event.Cancellable;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockBreakEvent;
import org.bukkit.event.block.BlockBurnEvent;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockExplodeEvent;
import org.bukkit.event.block.BlockFadeEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.BlockFormEvent;
import org.bukkit.event.block.BlockFromToEvent;
import org.bukkit.event.block.BlockGrowEvent;
import org.bukkit.event.block.BlockIgniteEvent;
import org.bukkit.event.block.BlockPistonExtendEvent;
import org.bukkit.event.block.BlockPistonRetractEvent;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.block.BlockSpreadEvent;
import org.bukkit.event.block.CauldronLevelChangeEvent;
import org.bukkit.event.block.EntityBlockFormEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.block.LeavesDecayEvent;
import org.bukkit.event.block.MoistureChangeEvent;
import org.bukkit.event.block.SpongeAbsorbEvent;
import org.bukkit.event.block.TNTPrimeEvent;
import org.bukkit.event.entity.EntityChangeBlockEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityExplodeEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.EntityInteractEvent;
import org.bukkit.event.entity.EntityPlaceEvent;
import org.bukkit.event.entity.EntityPortalEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.EntityTargetLivingEntityEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.entity.PlayerLeashEntityEvent;
import org.bukkit.event.entity.ProjectileLaunchEvent;
import org.bukkit.event.hanging.HangingBreakEvent;
import org.bukkit.event.hanging.HangingBreakByEntityEvent;
import org.bukkit.event.hanging.HangingPlaceEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerArmorStandManipulateEvent;
import org.bukkit.event.player.PlayerBucketEmptyEvent;
import org.bukkit.event.player.PlayerBucketFillEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractAtEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerItemConsumeEvent;
import org.bukkit.event.player.PlayerItemDamageEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerShearEntityEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.vehicle.VehicleDamageEvent;
import org.bukkit.event.vehicle.VehicleDestroyEvent;
import org.bukkit.event.vehicle.VehicleEnterEvent;
import org.bukkit.event.vehicle.VehicleEntityCollisionEvent;
import org.bukkit.event.weather.ThunderChangeEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scoreboard.Criteria;
import org.bukkit.scoreboard.DisplaySlot;
import org.bukkit.scoreboard.Objective;
import org.bukkit.scoreboard.Scoreboard;
import org.bukkit.scoreboard.Team;
import org.jetbrains.annotations.NotNull;

/** Generation-owned native implementation behind the owner-only paperManagedLobby host function. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidLiteralsInIfCondition",
        "PMD.CompareObjectsWithEquals", "PMD.AvoidCatchingThrowable", "PMD.ExhaustiveSwitchHasDefault",
        "PMD.AvoidFieldNameMatchingMethodName", "PMD.CloseResource", "PMD.NullAssignment"})
public final class PaperManagedLobbyBridge implements Listener, AutoCloseable {
    private static final MiniMessage MINI_MESSAGE = MiniMessage.miniMessage();
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private static final int MAX_ERROR_TEXT = 512;
    private static final long ACTIVATION_REFRESH_BACKOFF_NANOS = TimeUnit.SECONDS.toNanos(1);
    private final JavaPlugin plugin;
    private final UUID generationId;
    private final ManagedLobbyStore store;
    private final PaperManagedLobbyCoordinator coordinator;
    private final InvocationController invocations;
    private final int maximumPendingActions;
    private final ThreadPoolExecutor fileExecutor;
    private final Object lifecycleLock = new Object();
    private final AtomicInteger pendingActions = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean registered = new AtomicBoolean();
    private final AtomicBoolean activationRefresh = new AtomicBoolean();
    private final Set<CompletableFuture<?>> pendingFutures = ConcurrentHashMap.newKeySet();
    private final NamespacedKey itemKey;
    private final NamespacedKey generationKey;
    private final NamespacedKey menuSessionKey;
    private final NamespacedKey portalWandKey;
    private final Map<UUID, ManagedLobbyConfig.VisibilityMode> visibility = new ConcurrentHashMap<>();
    private final Map<CooldownKey, Long> cooldowns = new ConcurrentHashMap<>();
    private final Map<UUID, PortalOccupancy> occupiedPortals = new ConcurrentHashMap<>();
    private final Set<UUID> portalVisualizers = ConcurrentHashMap.newKeySet();
    private final Map<UUID, MenuSession> menuSessions = new ConcurrentHashMap<>();
    private final Map<UUID, SidebarState> sidebars = new ConcurrentHashMap<>();
    private final Map<UUID, Scoreboard> previousScoreboards = new ConcurrentHashMap<>();
    private final Map<UUID, PortalSelection> portalSelections = new ConcurrentHashMap<>();
    private final AtomicInteger scoreboardFrame = new AtomicInteger();
    private final AtomicLong portalTransition = new AtomicLong();
    private volatile ManagedLobbyConfig config;
    private volatile ManagedLobbyPortalIndex portalIndex = new ManagedLobbyPortalIndex(List.of());
    private volatile ScheduledTask enforcementTask;
    private volatile ScheduledTask scoreboardTask;
    private volatile ScheduledTask activationTask;
    private volatile ManagedLobbyStore.Snapshot preparedSnapshot;
    private volatile ManagedLobbyStore.Snapshot failedActivationSnapshot;
    private volatile long activationRefreshAfterNanos;
    private volatile boolean nativeActivated;

    public PaperManagedLobbyBridge(JavaPlugin plugin, UUID generationId, ManagedLobbyStore store,
            PaperManagedLobbyCoordinator coordinator, InvocationController invocations, int maximumPendingActions) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.generationId = Objects.requireNonNull(generationId, "generationId");
        this.store = Objects.requireNonNull(store, "store");
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
        this.invocations = Objects.requireNonNull(invocations, "invocations");
        if (maximumPendingActions < 1 || maximumPendingActions > 4_096) {
            throw new IllegalArgumentException("maximumPendingActions must be from 1 through 4096");
        }
        this.maximumPendingActions = maximumPendingActions;
        fileExecutor = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(maximumPendingActions), runnable -> {
                    Thread thread = Thread.ofPlatform().unstarted(runnable);
                    thread.setName("shamoo-managed-lobby-files-" + generationId);
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
        itemKey = new NamespacedKey("shamooruntime", "managed_lobby_item");
        generationKey = new NamespacedKey("shamooruntime", "managed_lobby_generation");
        menuSessionKey = new NamespacedKey("shamooruntime", "managed_lobby_menu");
        portalWandKey = new NamespacedKey("shamooruntime", "managed_lobby_portal_wand");
    }

    /** Invokes one bounded direct-host request and always returns a stage of an explicit data map. */
    public CompletionStage<Map<String, Object>> invoke(List<Object> arguments) {
        return invoke(arguments, false);
    }

    /** Invokes a request with the admission context captured by the Runtime host boundary. */
    public CompletionStage<Map<String, Object>> invoke(List<Object> arguments, boolean admitted) {
        if (closed.get()) {
            return CompletableFuture.completedFuture(failure("unavailable", "managed lobby generation is closed"));
        }
        final ManagedLobbyRequest request;
        try {
            if (arguments.size() != 1) {
                throw new IllegalArgumentException("paperManagedLobby accepts exactly one request object");
            }
            request = ManagedLobbyRequest.parse(arguments.getFirst());
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.completedFuture(failure("invalid", exception.getMessage()));
        }
        if (!admitted && mutates(request) && (nativeActivated || coordinator.ownsActive(this))) {
            return CompletableFuture.completedFuture(failure("unavailable",
                    "managed lobby mutation requires an admitted invocation"));
        }
        try {
            return switch (request.operation()) {
                case "status" -> CompletableFuture.completedFuture(status());
                case "ensure" -> explicit(submitFile(() -> {
                    store.ensure();
                    return success("ensured", Map.of("files", ManagedLobbyStore.FILES,
                            "directory", store.directory().toString()));
                }));
                case "read" -> explicit(read(request));
                case "write" -> explicit(write(request, admitted));
                case "reload" -> explicit(loadAndApply("reloaded", admitted));
                case "execute" -> explicit(execute(request, admitted));
                default -> CompletableFuture.completedFuture(failure("invalid",
                        "unsupported managed lobby operation"));
            };
        } catch (RuntimeException exception) {
            return CompletableFuture.completedFuture(failure(classify(exception), exception.getMessage()));
        }
    }

    private static boolean mutates(ManagedLobbyRequest request) {
        return switch (request.operation()) {
            case "write", "reload" -> true;
            case "execute" -> !Set.of("portal-list", "portal-info").contains(request.text("action"));
            default -> false;
        };
    }

    private CompletionStage<Map<String, Object>> read(ManagedLobbyRequest request) {
        return submitFile(() -> {
            store.ensure();
            String file = request.optionalText("file");
            return file == null
                    ? success("read", Map.of("files", store.readAll()))
                    : success("read", Map.of("file", file, "content", store.read(file)));
        });
    }

    private CompletionStage<Map<String, Object>> write(ManagedLobbyRequest request, boolean admitted) {
        boolean reload = request.optionalBoolean("reload", true);
        String file = request.text("file");
        String content = request.text("content");
        Map<String, Object> response = success("written", Map.of("file", file, "reloaded", reload));
        return submitFile(store::snapshot).thenApply(snapshot -> candidateWrite(snapshot, file, content, response))
                .thenCompose(this::prepareWrite).thenCompose(this::commitWrite)
                .thenCompose(committed -> reload
                        ? applyPrepared(committed.prepared(), committed.snapshot(), admitted)
                                .thenApply(ignored -> committed.response())
                        : CompletableFuture.completedFuture(committed.response()));
    }

    private CompletionStage<Map<String, Object>> loadAndApply(String state, boolean admitted) {
        return prepareLatestSnapshot()
                .thenCompose(prepared -> applyPrepared(prepared.prepared(), prepared.snapshot(), admitted)
                        .thenApply(ignored -> reloadSuccess(state, prepared)));
    }

    private CompletionStage<PreparedSnapshot> prepareLatestSnapshot() {
        return submitFile(store::snapshot).thenCompose(this::prepareSnapshot);
    }

    private CompletionStage<PreparedSnapshot> prepareSnapshot(ManagedLobbyStore.Snapshot snapshot) {
        final ManagedLobbyConfig candidate;
        try {
            candidate = ManagedLobbyConfig.parse(snapshot.files());
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        return prepare(candidate).thenApply(prepared -> new PreparedSnapshot(snapshot, prepared))
                .thenCompose(prepared -> submitFile(() -> {
                    store.requireUnchanged(prepared.snapshot());
                    return prepared;
                }));
    }

    private Map<String, Object> reloadSuccess(String state, PreparedSnapshot prepared) {
        ManagedLobbyConfig accepted = prepared.prepared().config();
        return success(state, Map.of(
                "files", ManagedLobbyStore.FILES,
                "messagesContent", prepared.snapshot().files().get("messages.yml"),
                "spawnConfigured", accepted.spawn() != null,
                "items", accepted.items().size(),
                "menus", accepted.menus().size(),
                "servers", accepted.transfers().servers().size(),
                "portals", accepted.portals().size()));
    }

    private CompletionStage<Map<String, Object>> execute(ManagedLobbyRequest request, boolean admitted) {
        if (config == null) {
            return CompletableFuture.completedFuture(failure("unavailable", "managed lobby is not initialized"));
        }
        if (!active()) {
            return CompletableFuture.completedFuture(failure("unavailable", "managed lobby generation is not active"));
        }
        return switch (request.text("action")) {
            case "setspawn" -> setSpawn(request.player(), admitted);
            case "spawn" -> withPlayer(request.player(), player -> {
                if (!managed(player)) {
                    return failure("unavailable", "player is outside managed lobby worlds");
                }
                Location spawn = spawn();
                if (spawn == null) {
                    return failure("unavailable", "no managed spawn is configured");
                }
                player.teleportAsync(spawn);
                return success("spawn-requested", Map.of("player", player.getUniqueId().toString()));
            });
            case "items" -> withPlayer(request.player(), player -> {
                if (!managed(player)) {
                    return failure("unavailable", "player is outside managed lobby worlds");
                }
                restoreItems(player);
                return success("items-restored", Map.of("player", player.getUniqueId().toString()));
            });
            case "menu" -> withPlayer(request.player(), player -> !managed(player)
                    ? failure("unavailable", "player is outside managed lobby worlds")
                    : openMenu(player, request.text("id"))
                            ? success("menu-opened", Map.of("id", request.text("id")))
                            : failure("unknown", "configured menu does not exist"));
            case "visibility" -> withPlayer(request.player(), player -> {
                if (!managed(player)) {
                    return failure("unavailable", "player is outside managed lobby worlds");
                }
                ManagedLobbyConfig.VisibilityMode mode = requestedVisibility(player, request.text("mode"));
                visibility.put(player.getUniqueId(), mode);
                scheduleVisibilityRefresh();
                return success("visibility-updated", Map.of("mode", mode.name().toLowerCase(Locale.ROOT)));
            });
            case "portal-wand" -> withPlayer(request.player(), player -> givePortalWand(player));
            case "portal-pos1" -> portalPosition(request.player(), true);
            case "portal-pos2" -> portalPosition(request.player(), false);
            case "portal-create" -> createPortal(request, admitted);
            case "portal-remove" -> portalAdmin(request.player(), () -> removePortal(request.text("id"), admitted));
            case "portal-list" -> CompletableFuture.completedFuture(portalList());
            case "portal-info" -> CompletableFuture.completedFuture(portalInfo(request.text("id")));
            case "portal-enable" -> portalAdmin(request.player(), () -> updatePortal(request.text("id"),
                    (current, portal) -> copyPortal(portal, true, portal.destination(), portal.action()),
                    "portal-enabled", "Portal activado.", admitted));
            case "portal-disable" -> portalAdmin(request.player(), () -> updatePortal(request.text("id"),
                    (current, portal) -> copyPortal(portal, false, portal.destination(), portal.action()),
                    "portal-disabled", "Portal desactivado.", admitted));
            case "portal-destination" -> portalAdmin(request.player(), () -> portalDestination(request, admitted));
            case "portal-visualize" -> withPlayer(request.player(), player -> {
                if (!portalEditor(player)) {
                    return failure("unavailable", "player lacks the portal editor permission");
                }
                boolean enabled = request.optionalBoolean("enabled", false);
                if (enabled) {
                    portalVisualizers.add(player.getUniqueId());
                } else {
                    portalVisualizers.remove(player.getUniqueId());
                }
                return success("portal-visualization-updated", Map.of("enabled", enabled,
                        "message", enabled ? "Visualización de portales activada."
                                : "Visualización de portales desactivada."));
            });
            default -> CompletableFuture.completedFuture(failure("invalid", "unsupported execute action"));
        };
    }

    private CompletionStage<Map<String, Object>> setSpawn(UUID playerId, boolean admitted) {
        return withPlayerValue(playerId, player -> {
            if (!managed(player)) {
                return new CapturedSpawn(null);
            }
            Location location = player.getLocation();
            return new CapturedSpawn(new ManagedLobbyConfig.Spawn(location.getWorld().getName(), location.getX(),
                    location.getY(), location.getZ(), location.getYaw(), location.getPitch()));
        }).thenCompose(captured -> {
            if (captured == null) {
                return CompletableFuture.completedFuture(failure("unknown", "player is not online"));
            }
            if (captured.spawn() == null) {
                return CompletableFuture.completedFuture(failure("unavailable",
                        "player is outside managed lobby worlds"));
            }
            ManagedLobbyConfig.Spawn spawn = captured.spawn();
            String encoded = ManagedLobbyConfig.encodeSpawn(spawn);
            Map<String, Object> response = success("spawn-set", Map.of(
                    "world", spawn.world(), "x", spawn.x(), "y", spawn.y(), "z", spawn.z()));
            return submitFile(store::snapshot)
                    .thenApply(snapshot -> candidateWrite(snapshot, "spawn.yml", encoded, response))
                    .thenCompose(this::prepareWrite).thenCompose(this::commitWrite)
                    .thenCompose(committed -> applyPrepared(committed.prepared(), committed.snapshot(), admitted)
                            .thenApply(ignored -> committed.response()));
        });
    }

    private CompletionStage<Map<String, Object>> portalPosition(UUID playerId, boolean first) {
        return withPlayer(playerId, player -> {
            if (!portalEditor(player)) {
                return failure("unavailable", "player lacks the portal editor permission");
            }
            PortalPosition position = portalPosition(player.getLocation());
            portalSelections.compute(playerId, (ignored, selection) -> first
                    ? new PortalSelection(position, selection == null ? null : selection.second())
                    : new PortalSelection(selection == null ? null : selection.first(), position));
            return success(first ? "portal-pos1" : "portal-pos2", Map.of(
                    "position", position.data(), "message", first
                            ? "Primera posición guardada." : "Segunda posición guardada."));
        });
    }

    private CompletionStage<Map<String, Object>> portalAdmin(UUID playerId,
            Supplier<CompletionStage<Map<String, Object>>> operation) {
        return withPlayerValue(playerId, this::portalEditor).thenCompose(authorized -> Boolean.TRUE.equals(authorized)
                ? operation.get() : CompletableFuture.completedFuture(failure("unavailable",
                        "player is offline, outside managed worlds, or lacks portal editor permission")));
    }

    private CompletionStage<Map<String, Object>> createPortal(ManagedLobbyRequest request, boolean admitted) {
        UUID playerId = request.player();
        return withPlayerValue(playerId, player -> portalEditor(player) ? portalSelections.get(playerId) : null)
                .thenCompose(selection -> {
                    if (selection == null || selection.first() == null || selection.second() == null) {
                        return CompletableFuture.completedFuture(failure("unavailable",
                                "portal selection requires pos1 and pos2"));
                    }
                    if (!selection.first().world().equals(selection.second().world())) {
                        return CompletableFuture.completedFuture(failure("invalid",
                                "portal positions must be in the same world"));
                    }
                    String destination = request.optionalText("destination");
                    if (destination != null && config.transfers().enabled(destination) == null) {
                        return CompletableFuture.completedFuture(failure("unknown",
                                "destination is not an enabled configured server"));
                    }
                    PortalBounds bounds = PortalBounds.from(selection);
                    ManagedLobbyPortalIndex.Portal portal = new ManagedLobbyPortalIndex.Portal(request.text("id"),
                            bounds.world(), bounds.minimumX(), bounds.minimumY(), bounds.minimumZ(),
                            bounds.maximumX(), bounds.maximumY(), bounds.maximumZ(),
                            request.optionalBoolean("enabled", true), request.optionalText("permission"),
                            request.optionalInteger("priority", 0),
                            request.optionalInteger("cooldown-ms", (int) config.defaultPortalCooldownMillis()),
                            destination, destination == null ? ManagedLobbyConfig.Action.none()
                                    : new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.CONNECT, destination),
                            request.optionalBoolean("visualize", false));
                    return editPortal(portal.id(), true, (current, existing) -> portal,
                            "portal-created", "Portal creado correctamente.", admitted);
                });
    }

    private CompletionStage<Map<String, Object>> editPortal(String id, boolean allowMissing,
            PortalMutation mutation, String state, String message, boolean admitted) {
        return submitFile(() -> store.transaction(locked -> {
            ManagedLobbyStore.Snapshot snapshot = locked.snapshot();
            ManagedLobbyConfig current = ManagedLobbyConfig.parse(snapshot.files());
            ManagedLobbyPortalIndex.Portal existing = current.portals().stream()
                    .filter(portal -> portal.id().equals(id)).findFirst().orElse(null);
            if (allowMissing) {
                requirePortalCreateAvailable(existing, id);
            }
            if (existing == null && !allowMissing) {
                throw new UnknownResourceException("configured portal does not exist");
            }
            ManagedLobbyPortalIndex.Portal replacement = mutation.apply(current, existing);
            List<ManagedLobbyPortalIndex.Portal> portals = new ArrayList<>(current.portals());
            portals.removeIf(portal -> portal.id().equals(id));
            if (replacement != null) {
                portals.add(replacement);
            }
            ManagedLobbyPortalIndex.Portal result = replacement == null ? existing : replacement;
            String encoded = ManagedLobbyConfig.encodePortals(portals);
            return candidateWrite(snapshot, "portals.yml", encoded, success(state,
                    Map.of("portal", ManagedLobbyConfig.portalData(result), "message", message)));
        })).thenCompose(this::prepareWrite).thenCompose(this::commitWrite)
                .thenCompose(committed -> applyPrepared(committed.prepared(), committed.snapshot(), admitted)
                        .thenApply(ignored -> committed.response()));
    }

    static void requirePortalCreateAvailable(ManagedLobbyPortalIndex.Portal existing, String id) {
        if (existing != null) {
            throw new IllegalArgumentException("configured portal id already exists: " + id);
        }
    }

    private WriteCandidate candidateWrite(ManagedLobbyStore.Snapshot snapshot, String file, String content,
            Map<String, Object> response) {
        Map<String, String> files = new LinkedHashMap<>(snapshot.files());
        files.put(file, content);
        return new WriteCandidate(snapshot, file, content, ManagedLobbyConfig.parse(files), response);
    }

    private CompletionStage<PreparedWrite> prepareWrite(WriteCandidate candidate) {
        return prepare(candidate.config()).thenApply(prepared -> new PreparedWrite(candidate.snapshot(),
                candidate.file(), candidate.content(), prepared, candidate.response()));
    }

    private CompletionStage<CommittedWrite> commitWrite(PreparedWrite prepared) {
        return submitFile(() -> {
            synchronized (lifecycleLock) {
                requireOpen();
                ManagedLobbyStore.Snapshot snapshot = store.writeIfUnchanged(
                        prepared.snapshot(), prepared.file(), prepared.content());
                return new CommittedWrite(prepared.prepared(), prepared.response(), snapshot);
            }
        });
    }

    private CompletionStage<Map<String, Object>> removePortal(String id, boolean admitted) {
        return editPortal(id, false, (current, existing) -> null,
                "portal-removed", "Portal eliminado.", admitted);
    }

    private Map<String, Object> portalList() {
        List<Map<String, Object>> portals = config.portals().stream().map(ManagedLobbyConfig::portalData).toList();
        return success("portal-list", Map.of("portals", portals, "count", portals.size(),
                "message", portals.isEmpty() ? "No hay portales configurados."
                        : "Portales configurados: " + portals.size()));
    }

    private Map<String, Object> portalInfo(String id) {
        ManagedLobbyPortalIndex.Portal portal = portal(id);
        return portal == null ? failure("unknown", "configured portal does not exist")
                : success("portal-info", Map.of("portal", ManagedLobbyConfig.portalData(portal),
                        "message", "Información del portal " + id + '.'));
    }

    private CompletionStage<Map<String, Object>> updatePortal(String id,
            PortalMutation update, String state, String message, boolean admitted) {
        return editPortal(id, false, update, state, message, admitted);
    }

    private CompletionStage<Map<String, Object>> portalDestination(ManagedLobbyRequest request, boolean admitted) {
        String type = request.text("type");
        String target = request.optionalText("target");
        String description = switch (type) {
            case "server" -> "servidor " + target;
            case "spawn" -> "punto de aparición";
            case "menu" -> "menú " + target;
            default -> throw new IllegalArgumentException("unsupported portal destination type: " + type);
        };
        return updatePortal(request.text("id"),
                (current, portal) -> portalDestination(current, portal, type, target),
                "portal-destination", "Destino del portal actualizado: " + description + '.', admitted);
    }

    static ManagedLobbyPortalIndex.Portal portalDestination(ManagedLobbyConfig current,
            ManagedLobbyPortalIndex.Portal portal, String type, String target) {
        return switch (type) {
            case "server" -> {
                if (current.transfers().enabled(target) == null) {
                    throw new UnknownResourceException("destination is not an enabled configured server");
                }
                yield copyPortal(portal, portal.enabled(), target,
                        new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.CONNECT, target));
            }
            case "spawn" -> copyPortal(portal, portal.enabled(), null,
                    new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.SPAWN, null));
            case "menu" -> {
                if (!current.menus().containsKey(target)) {
                    throw new UnknownResourceException("destination is not a configured menu");
                }
                yield copyPortal(portal, portal.enabled(), null,
                        new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.MENU, target));
            }
            default -> throw new IllegalArgumentException("unsupported portal destination type: " + type);
        };
    }

    private ManagedLobbyPortalIndex.Portal portal(String id) {
        return config.portals().stream().filter(portal -> portal.id().equals(id)).findFirst().orElse(null);
    }

    private static ManagedLobbyPortalIndex.Portal copyPortal(ManagedLobbyPortalIndex.Portal portal,
            boolean enabled, String destination, ManagedLobbyConfig.Action action) {
        return new ManagedLobbyPortalIndex.Portal(portal.id(), portal.world(), portal.minimumX(), portal.minimumY(),
                portal.minimumZ(), portal.maximumX(), portal.maximumY(), portal.maximumZ(), enabled,
                portal.permission(), portal.priority(), portal.cooldownMillis(), destination, action,
                portal.visualize());
    }

    private CompletionStage<PreparedConfig> prepare(ManagedLobbyConfig candidate) {
        return scheduleGlobal(() -> prepareNative(candidate));
    }

    private PreparedConfig prepareNative(ManagedLobbyConfig candidate) {
        requireOpen();
        candidate.messages().values().forEach(this::component);
        candidate.titles().values().forEach(title -> {
            component(title.title());
            component(title.subtitle());
        });
        candidate.transfers().servers().values().forEach(server -> component(server.displayName()));
        candidate.items().values().forEach(item -> item(item, null));
        UUID menuToken = UUID.randomUUID();
        candidate.menus().values().forEach(menu -> {
            component(menu.title());
            menu.slots().values().forEach(slot -> item(slot, menuToken));
        });
        candidate.sidebar().titleFrames().forEach(this::component);
        candidate.sidebar().lines().forEach(this::component);
        candidate.sounds().values().forEach(sound -> nativeSound(sound.sound()));
        candidate.particles().values().forEach(particle -> {
            Particle nativeParticle = nativeParticle(particle.particle());
            if (nativeParticle.getDataType() != Void.class) {
                throw new IllegalArgumentException("particle requires unsupported arbitrary data: "
                        + particle.particle());
            }
        });
        Map<String, World> worlds = new LinkedHashMap<>();
        for (ManagedLobbyConfig.ManagedWorld settings : candidate.worlds()) {
            World world = Bukkit.getWorld(settings.name());
            if (world == null) {
                throw new IllegalStateException("configured managed world is unavailable: " + settings.name());
            }
            settings.gameRules().forEach((name, value) -> validateGameRule(name, value));
            worlds.put(settings.name(), world);
        }
        if (candidate.spawn() != null && !worlds.containsKey(candidate.spawn().world())) {
            throw new IllegalArgumentException("configured spawn must reference an available managed world");
        }
        for (ManagedLobbyPortalIndex.Portal portal : candidate.portals()) {
            World world = worlds.get(portal.world());
            if (world == null || portal.minimumY() < world.getMinHeight() || portal.maximumY() > world.getMaxHeight()) {
                throw new IllegalArgumentException("portal bounds are outside an available managed world: "
                        + portal.id());
            }
        }
        return new PreparedConfig(candidate, new ManagedLobbyPortalIndex(candidate.portals()));
    }

    private CompletionStage<Void> applyPrepared(PreparedConfig prepared, ManagedLobbyStore.Snapshot snapshot,
            boolean admitted) {
        return applyPrepared(prepared, snapshot, admitted, false);
    }

    private CompletionStage<Void> applyPrepared(PreparedConfig prepared, ManagedLobbyStore.Snapshot snapshot,
            boolean admitted, boolean recheckStandbyAdmission) {
        return submitFile(() -> {
            store.requireUnchanged(snapshot);
            return snapshot.version();
        }).thenCompose(version -> scheduleGlobal(() -> {
            synchronized (lifecycleLock) {
                requireOpen();
                boolean effectiveAdmission = admitted
                        || recheckStandbyAdmission && invocations.snapshot().accepting();
                store.runAtVersion(version, () -> applyPreparedLocked(prepared, snapshot, effectiveAdmission));
            }
            return null;
        }));
    }

    void applyPreparedLocked(PreparedConfig prepared, ManagedLobbyStore.Snapshot snapshot, boolean admitted) {
        ManagedLobbyConfig next = prepared.config();
        AppliedState previous = new AppliedState(config, portalIndex, preparedSnapshot,
                enforcementTask, scoreboardTask, activationTask);
        if (!admitted && previous.config() != null && (nativeActivated || coordinator.ownsActive(this))) {
            throw new IllegalStateException("managed lobby admission closed during active reload");
        }
        ScheduledTask newEnforcement = null;
        ScheduledTask newScoreboard = null;
        ScheduledTask newActivation = null;
        boolean newlyRegistered = false;
        NativeActivation nativeActivation = null;
        try {
            if (registered.compareAndSet(false, true)) {
                newlyRegistered = true;
                plugin.getServer().getPluginManager().registerEvents(this, plugin);
            }
            newEnforcement = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    ignored -> enforce(), next.enforcementTicks(), next.enforcementTicks());
            newScoreboard = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                    ignored -> updatePresentation(), next.sidebar().intervalTicks(), next.sidebar().intervalTicks());
            if (!admitted) {
                newActivation = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin,
                        ignored -> activateWhenAdmitted(), 1L, 1L);
            }
            requireOpen();
            if (admitted) {
                nativeActivation = beginNativeActivation(next);
            }
            config = next;
            portalIndex = prepared.portalIndex();
            preparedSnapshot = snapshot;
            enforcementTask = newEnforcement;
            scoreboardTask = newScoreboard;
            activationTask = newActivation;
            if (nativeActivation != null) {
                commitNativeActivation(nativeActivation);
            }
        } catch (RuntimeException | Error failure) {
            restoreAppliedState(previous);
            cancelOnFailure(newEnforcement, failure);
            cancelOnFailure(newScoreboard, failure);
            cancelOnFailure(newActivation, failure);
            if (newlyRegistered) {
                unregisterOnFailure(failure);
            }
            if (nativeActivation != null) {
                rollbackNativeActivation(nativeActivation, failure);
            }
            throw failure;
        }
        finishAppliedState(previous, next);
    }

    void activateWhenAdmitted() {
        final ManagedLobbyStore.Snapshot snapshot;
        synchronized (lifecycleLock) {
            if (closed.get() || config == null || !invocations.snapshot().accepting()
                    || !refreshBackoffElapsed(System.nanoTime(), activationRefreshAfterNanos)
                    || !activationRefresh.compareAndSet(false, true)) {
                return;
            }
            snapshot = Objects.requireNonNull(preparedSnapshot, "prepared managed lobby snapshot");
        }
        submitFile(() -> {
            store.requireUnchanged(snapshot);
            return snapshot.version();
        }).thenCompose(version -> scheduleGlobal(() -> activateVerifiedSnapshot(snapshot, version)))
                .whenComplete((activated, failure) -> finishActivationAttempt(Boolean.TRUE.equals(activated), failure));
    }

    private boolean activateVerifiedSnapshot(ManagedLobbyStore.Snapshot snapshot, long version) {
        synchronized (lifecycleLock) {
            if (closed.get() || !invocations.snapshot().accepting() || !snapshot.equals(preparedSnapshot)) {
                return false;
            }
            store.runAtVersion(version, this::activateNative);
            ScheduledTask previousActivation = activationTask;
            activationTask = null;
            postCommitCleanup(() -> cancel(previousActivation), "standby activation task");
            return true;
        }
    }

    void activateNative() {
        NativeActivation activation = beginNativeActivation(config);
        try {
            commitNativeActivation(activation);
        } catch (RuntimeException | Error failure) {
            rollbackNativeActivation(activation, failure);
            throw failure;
        }
    }

    private NativeActivation beginNativeActivation(ManagedLobbyConfig candidate) {
        PaperManagedLobbyCoordinator.Activation activation = coordinator.activate(this);
        try {
            PaperManagedLobbyBridge previous = activation.previous() == this ? null : activation.previous();
            return new NativeActivation(activation, previous, candidate, captureWorldPolicy(candidate),
                    new AtomicBoolean());
        } catch (RuntimeException | Error failure) {
            try {
                coordinator.rollback(this, activation);
            } catch (RuntimeException | Error rollbackFailure) {
                failure.addSuppressed(rollbackFailure);
            }
            throw failure;
        }
    }

    private void commitNativeActivation(NativeActivation activation) {
        boolean reset = activation.activation().cold() && activation.config().join().reset();
        applyWorlds(activation.config());
        for (Player player : Bukkit.getOnlinePlayers()) {
            boolean accepted = player.getScheduler().execute(plugin, () -> {
                if (!activation.committed().get() || closed.get() || !coordinator.isActive(this)) {
                    return;
                }
                if (activation.previous() != null) {
                    activation.previous().cleanupOwnedPlayer(player, false);
                }
                if (!closed.get() && managed(player)) {
                    applyPlayer(player, reset);
                    applyVisibility(player);
                }
            }, null, 1L);
            if (!accepted) {
                throw new IllegalStateException("managed lobby player activation scheduling was rejected");
            }
        }
        scheduleVisibilityRefresh(activation.committed()::get, true);
        coordinator.commit(this, activation.activation());
        nativeActivated = true;
        activation.committed().set(true);
    }

    private void rollbackNativeActivation(NativeActivation activation, Throwable failure) {
        activation.committed().set(false);
        restoreWorldPolicy(activation.worldPolicy(), failure);
        PaperManagedLobbyBridge rollbackOwner = activation.previous();
        try {
            PaperManagedLobbyBridge restored = coordinator.rollback(this, activation.activation());
            if (restored != null && restored != this) {
                rollbackOwner = restored;
            }
        } catch (RuntimeException | Error rollbackFailure) {
            failure.addSuppressed(rollbackFailure);
        }
        if (rollbackOwner != null) {
            try {
                rollbackOwner.reactivateNative();
            } catch (RuntimeException | Error reactivationFailure) {
                failure.addSuppressed(reactivationFailure);
            }
        }
    }

    private void restoreAppliedState(AppliedState state) {
        config = state.config();
        portalIndex = state.portalIndex();
        preparedSnapshot = state.snapshot();
        enforcementTask = state.enforcementTask();
        scoreboardTask = state.scoreboardTask();
        activationTask = state.activationTask();
    }

    private void cancelOnFailure(ScheduledTask task, Throwable failure) {
        if (task == null) {
            return;
        }
        try {
            task.cancel();
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void unregisterOnFailure(Throwable failure) {
        try {
            HandlerList.unregisterAll(this);
            registered.set(false);
        } catch (RuntimeException | Error cleanupFailure) {
            failure.addSuppressed(cleanupFailure);
        }
    }

    private void finishAppliedState(AppliedState previous, ManagedLobbyConfig next) {
        postCommitCleanup(() -> cancel(previous.enforcementTask()), "previous enforcement task");
        postCommitCleanup(() -> cancel(previous.scoreboardTask()), "previous scoreboard task");
        postCommitCleanup(() -> cancel(previous.activationTask()), "previous activation task");
        postCommitCleanup(this::invalidateMenuSessions, "managed menu sessions");
        postCommitCleanup(() -> cleanupRemovedWorlds(previous.config(), next), "removed-world player state");
    }

    private void postCommitCleanup(Runnable cleanup, String description) {
        try {
            cleanup.run();
        } catch (Throwable failure) {
            if (plugin.getLogger().isLoggable(Level.WARNING)) {
                plugin.getLogger().log(Level.WARNING,
                        "Unable to clean up " + description + " after lobby commit", failure);
            }
        }
    }

    private void finishActivationAttempt(boolean activated, Throwable failure) {
        Throwable activationFailure = failure == null ? null : unwrap(failure);
        if (activationFailure != null && snapshotFailure(activationFailure) && !closed.get()) {
            refreshStaleStandby();
            return;
        }
        activationRefresh.set(false);
        if (activated) {
            failedActivationSnapshot = null;
            activationRefreshAfterNanos = 0;
        }
        if (activationFailure != null && !closed.get() && plugin.getLogger().isLoggable(Level.FINE)) {
            plugin.getLogger().log(Level.FINE, "Managed lobby standby activation will retry", activationFailure);
        }
    }

    private static boolean snapshotFailure(Throwable failure) {
        return failure instanceof ManagedLobbyStore.StaleSnapshotException
                || failure instanceof IOException || failure instanceof UncheckedIOException;
    }

    private void refreshStaleStandby() {
        long now = System.nanoTime();
        if (!refreshBackoffElapsed(now, activationRefreshAfterNanos)) {
            activationRefresh.set(false);
            return;
        }
        AtomicReference<ManagedLobbyStore.Snapshot> attempted = new AtomicReference<>();
        submitFile(store::snapshot).thenCompose(snapshot -> {
            attempted.set(snapshot);
            return shouldRetryFailedSnapshot(failedActivationSnapshot, snapshot)
                    ? prepareSnapshot(snapshot)
                    : CompletableFuture.failedFuture(new UnchangedFailedSnapshotException());
        }).thenCompose(prepared -> applyPrepared(prepared.prepared(), prepared.snapshot(), false, true))
                .whenComplete((ignored, failure) -> {
                    activationRefresh.set(false);
                    activationRefreshAfterNanos = failure == null ? 0
                            : System.nanoTime() + ACTIVATION_REFRESH_BACKOFF_NANOS;
                    if (failure == null) {
                        failedActivationSnapshot = null;
                    } else if (!(unwrap(failure) instanceof UnchangedFailedSnapshotException)) {
                        failedActivationSnapshot = attempted.get();
                    }
                    Throwable refreshFailure = failure == null ? null : unwrap(failure);
                    if (refreshFailure != null && !(refreshFailure instanceof UnchangedFailedSnapshotException)
                            && !closed.get()) {
                        if (plugin.getLogger().isLoggable(Level.FINE)) {
                            plugin.getLogger().log(Level.FINE,
                                    "Managed lobby standby refresh did not settle; activation will retry",
                                    refreshFailure);
                        }
                    }
                });
    }

    static boolean shouldRetryFailedSnapshot(ManagedLobbyStore.Snapshot failed,
            ManagedLobbyStore.Snapshot current) {
        return failed == null || !failed.equals(current);
    }

    static boolean refreshBackoffElapsed(long now, long retryAfter) {
        return now >= retryAfter;
    }

    private void enforce() {
        if (!active()) {
            return;
        }
        applyWorlds();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(plugin, () -> {
                if (!active() || !player.isOnline() || !managed(player)) {
                    return;
                }
                restoreItems(player);
                Location spawn = spawn();
                if (spawn != null && player.getLocation().getY() < config.voidRescueY()) {
                    player.teleportAsync(spawn);
                }
            }, null, 1L);
        }
        cooldowns.entrySet().removeIf(entry -> entry.getValue() < System.currentTimeMillis() - 600_000L);
    }

    private void updatePresentation() {
        if (!active()) {
            return;
        }
        scoreboardFrame.incrementAndGet();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(plugin, () -> {
                if (!active()) {
                    return;
                }
                if (managed(player)) {
                    updateSidebar(player);
                    applyVisibility(player);
                    visualizePortals(player);
                } else {
                    leaveManagedPlayer(player);
                }
            }, null, 1L);
        }
    }

    private void applyWorlds() {
        applyWorlds(config);
    }

    private void applyWorlds(ManagedLobbyConfig current) {
        if (current == null) {
            return;
        }
        for (ManagedLobbyConfig.ManagedWorld settings : current.worlds()) {
            World world = Bukkit.getWorld(settings.name());
            if (world == null) {
                continue;
            }
            if (settings.time() != null) {
                world.setTime(settings.time());
            }
            if (settings.storm() != null) {
                world.setStorm(settings.storm());
            }
            if (settings.thundering() != null) {
                world.setThundering(settings.thundering());
            }
            settings.gameRules().forEach((name, value) -> applyGameRule(world, name, value));
        }
    }

    static List<WorldPolicySnapshot> captureWorldPolicy(ManagedLobbyConfig current) {
        List<WorldPolicySnapshot> result = new ArrayList<>();
        for (ManagedLobbyConfig.ManagedWorld settings : current.worlds()) {
            World world = Bukkit.getWorld(settings.name());
            if (world == null) {
                continue;
            }
            Map<String, String> gameRules = new LinkedHashMap<>();
            for (String name : settings.gameRules().keySet()) {
                GameRule<?> rule = GameRule.getByName(name);
                if (rule == null) {
                    throw new IllegalArgumentException("unknown game rule: " + name);
                }
                Object value = world.getGameRuleValue(rule);
                if (value == null) {
                    throw new IllegalStateException("game rule has no current value: " + name);
                }
                gameRules.put(name, value.toString());
            }
            result.add(new WorldPolicySnapshot(world,
                    settings.time() == null ? null : world.getTime(),
                    settings.storm() == null ? null : world.hasStorm(),
                    settings.thundering() == null ? null : world.isThundering(), gameRules));
        }
        return List.copyOf(result);
    }

    static void restoreWorldPolicy(List<WorldPolicySnapshot> snapshots, Throwable failure) {
        for (WorldPolicySnapshot snapshot : snapshots) {
            if (snapshot.time() != null) {
                restoreWorldValue(() -> snapshot.world().setTime(snapshot.time()), failure);
            }
            if (snapshot.storm() != null) {
                restoreWorldValue(() -> snapshot.world().setStorm(snapshot.storm()), failure);
            }
            if (snapshot.thundering() != null) {
                restoreWorldValue(() -> snapshot.world().setThundering(snapshot.thundering()), failure);
            }
            snapshot.gameRules().forEach((name, value) ->
                    restoreWorldValue(() -> applyGameRule(snapshot.world(), name, value), failure));
        }
    }

    private static void restoreWorldValue(Runnable restore, Throwable failure) {
        try {
            restore.run();
        } catch (RuntimeException | Error restoreFailure) {
            failure.addSuppressed(restoreFailure);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent event) {
        if (!active()) {
            return;
        }
        Player player = event.getPlayer();
        Location target = config.join().teleport() ? spawn() : null;
        if (!managed(player) && target == null) {
            return;
        }
        if (config.join().suppressMessage()) {
            event.joinMessage(null);
        }
        if (target != null) {
            player.teleportAsync(target).thenAccept(teleported -> {
                if (Boolean.TRUE.equals(teleported)) {
                    scheduleJoinedPlayer(player);
                }
            });
        } else {
            scheduleJoinedPlayer(player);
        }
    }

    private void scheduleJoinedPlayer(Player player) {
        player.getScheduler().execute(plugin, () -> {
            if (!active() || !managed(player)) {
                return;
            }
            applyPlayer(player, config.join().reset());
            scheduleVisibilityRefresh();
            showWelcome(player);
        }, null, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent event) {
        if (!active() || !managedRespawn(config, event.getPlayer().getWorld().getName(),
                event.getRespawnLocation().getWorld().getName())) {
            return;
        }
        Location spawn = spawn();
        if (spawn != null) {
            event.setRespawnLocation(spawn);
        }
        Player player = event.getPlayer();
        player.getScheduler().execute(plugin, () -> {
            if (active() && managed(player)) {
                applyPlayer(player, config.join().reset());
                scheduleVisibilityRefresh();
            }
        }, null, 1L);
    }

    static boolean managedRespawn(ManagedLobbyConfig current, String currentWorld, String destinationWorld) {
        return current.manages(currentWorld) || current.manages(destinationWorld);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDamage(EntityDamageEvent event) {
        Player victim = event.getEntityType() == EntityType.PLAYER && event.getEntity() instanceof Player player
                ? player : null;
        if (active() && victim != null && protectedPlayer(victim)) {
            event.setCancelled(true);
            victim.setFireTicks(0);
            victim.setFallDistance(0);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFood(FoodLevelChangeEvent event) {
        if (active() && event.getEntity() instanceof Player player && protectedPlayer(player)) {
            event.setCancelled(true);
            player.setFoodLevel(20);
            player.setSaturation(5.0F);
            player.setExhaustion(0.0F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onExhaustion(EntityExhaustionEvent event) {
        if (active() && event.getEntity() instanceof Player player && protectedPlayer(player)) {
            event.setCancelled(true);
            player.setExhaustion(0.0F);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTarget(EntityTargetLivingEntityEvent event) {
        if (active() && event.getTarget() instanceof Player player && protectedPlayer(player)) {
            event.setCancelled(true);
            event.setTarget(null);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent event) {
        if (!active() || event.getTo() == null) {
            return;
        }
        Player player = event.getPlayer();
        occupiedPortals.remove(player.getUniqueId());
        switch (managedWorldTransition(config, event.getFrom().getWorld().getName(),
                event.getTo().getWorld().getName())) {
            case ENTER -> scheduleManagedEntry(player);
            case LEAVE -> {
                leaveManagedPlayer(player);
                scheduleVisibilityRefresh();
            }
            case NONE -> {
                // Teleports intentionally reset portal occupancy without triggering portal actions.
            }
            default -> throw new IllegalStateException("unknown managed world transition");
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMove(PlayerMoveEvent event) {
        if (!active() || event instanceof PlayerTeleportEvent || event.isCancelled() || event.getTo() == null) {
            return;
        }
        Location destination = event.getTo();
        ManagedWorldTransition worldTransition = managedWorldTransition(config,
                event.getFrom().getWorld().getName(), destination.getWorld().getName());
        if (worldTransition == ManagedWorldTransition.LEAVE) {
            occupiedPortals.remove(event.getPlayer().getUniqueId());
            event.getPlayer().getScheduler().execute(plugin, () -> {
                if (active()) {
                    leaveManagedPlayer(event.getPlayer());
                    scheduleVisibilityRefresh();
                }
            }, null, 1L);
            return;
        }
        if (worldTransition == ManagedWorldTransition.ENTER) {
            scheduleManagedEntry(event.getPlayer());
        }
        if (!managed(destination.getWorld())) {
            occupiedPortals.remove(event.getPlayer().getUniqueId());
            return;
        }
        Location spawn = spawn();
        if (spawn != null && destination.getY() < config.voidRescueY()) {
            event.setTo(spawn);
            occupiedPortals.remove(event.getPlayer().getUniqueId());
            return;
        }
        if (sameBlock(event.getFrom(), destination)) {
            return;
        }
        ManagedLobbyPortalIndex.Portal portal = portalIndex.highest(destination.getWorld().getName(),
                destination.getX(), destination.getY(), destination.getZ());
        UUID playerId = event.getPlayer().getUniqueId();
        PortalOccupancy previous = occupiedPortals.get(playerId);
        if (portal == null) {
            occupiedPortals.remove(playerId);
            return;
        }
        if (previous != null && portal.id().equals(previous.portal())) {
            return;
        }
        PortalOccupancy transition = new PortalOccupancy(portal.id(), portalTransition.incrementAndGet());
        occupiedPortals.put(playerId, transition);
        CooldownKey key = new CooldownKey(playerId, "portal:" + portal.id());
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key, 0L);
        if (readyAt > now) {
            cooldownFeedback(event.getPlayer(), "portal-cooldown", readyAt - now);
            return;
        }
        if (portal.permission() != null && !event.getPlayer().hasPermission(portal.permission())) {
            feedback(event.getPlayer(), "sin-permiso", Map.of(), "<#FF5C7A>No tienes permiso.</#FF5C7A>");
            return;
        }
        if (!queuePortalAction(event.getPlayer(), portal, transition)) {
            occupiedPortals.remove(playerId, transition);
        }
    }

    static ManagedWorldTransition managedWorldTransition(ManagedLobbyConfig current,
            String fromWorld, String toWorld) {
        boolean fromManaged = current.manages(fromWorld);
        boolean toManaged = current.manages(toWorld);
        if (!fromManaged && toManaged) {
            return ManagedWorldTransition.ENTER;
        }
        if (fromManaged && !toManaged) {
            return ManagedWorldTransition.LEAVE;
        }
        return ManagedWorldTransition.NONE;
    }

    private void scheduleManagedEntry(Player player) {
        player.getScheduler().execute(plugin, () -> {
            if (active() && managed(player)) {
                applyPlayer(player, config.join().reset());
                scheduleVisibilityRefresh();
            }
        }, null, 1L);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteract(PlayerInteractEvent event) {
        if (!active()) {
            return;
        }
        boolean artifact = managedArtifact(event.getItem());
        if (artifact) {
            event.setCancelled(true);
        }
        if (!managed(event.getPlayer())) {
            return;
        }
        if (event.getHand() == EquipmentSlot.HAND && portalWand(event.getItem())) {
            captureWandPosition(event);
            return;
        }
        if (protectedPlayer(event.getPlayer()) && (event.getAction() == org.bukkit.event.block.Action.PHYSICAL
                || event.getClickedBlock() != null)) {
            event.setCancelled(true);
        }
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        String id = managedItemId(event.getItem());
        ManagedLobbyConfig.LobbyItem item = id == null ? null : config.items().get(id);
        if (item == null) {
            return;
        }
        event.setCancelled(true);
        if (!itemTrigger(event.getAction())) {
            return;
        }
        long now = System.currentTimeMillis();
        CooldownKey key = new CooldownKey(event.getPlayer().getUniqueId(), "item:" + id);
        long readyAt = cooldowns.getOrDefault(key, 0L);
        if (readyAt > now) {
            cooldownFeedback(event.getPlayer(), "item-cooldown", readyAt - now);
        } else if (executeAction(event.getPlayer(), item.action())) {
            cooldowns.put(key, now + item.cooldownMillis());
        }
        restoreItemDelayed(event.getPlayer(), item);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!active() || !(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean artifact = managedArtifact(event.getCurrentItem()) || managedArtifact(event.getCursor());
        boolean managedMenu = managedMenuInventory(event.getView().getTopInventory());
        if (artifact || managedMenu) {
            event.setCancelled(true);
        }
        if (!managed(player)) {
            return;
        }
        MenuSession session = menuSession(event.getView().getTopInventory(), player);
        if (session == null) {
            if (protectedPlayer(player)) {
                event.setCancelled(true);
            }
            return;
        }
        event.setCancelled(true);
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= session.inventory().getSize()) {
            return;
        }
        ManagedLobbyConfig.MenuSlot definition = session.menu().slots().get(slot);
        ItemStack current = event.getCurrentItem();
        if (definition == null || current == null || !session.token().toString().equals(menuToken(current))) {
            return;
        }
        executeAction(player, definition.action());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        if (active() && (managedArtifact(event.getOldCursor())
                || managedMenuInventory(event.getView().getTopInventory()))) {
            event.setCancelled(true);
            return;
        }
        if (active() && event.getWhoClicked() instanceof Player player
                && menuSession(event.getView().getTopInventory(), player) != null) {
            event.setCancelled(true);
        } else if (active() && event.getWhoClicked() instanceof Player player && protectedPlayer(player)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryMove(InventoryMoveItemEvent event) {
        if (active() && (managedArtifact(event.getItem()) || protectedInventory(event.getSource())
                || protectedInventory(event.getDestination()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryPickup(InventoryPickupItemEvent event) {
        if (active() && (managedArtifact(event.getItem().getItemStack())
                || protectedInventory(event.getInventory()))) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onInventoryClose(InventoryCloseEvent event) {
        if (event.getPlayer() instanceof Player player) {
            MenuSession session = menuSessions.get(player.getUniqueId());
            if (session != null && session.inventory() == event.getInventory()) {
                menuSessions.remove(player.getUniqueId(), session);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onDrop(PlayerDropItemEvent event) {
        boolean artifact = managedArtifact(event.getItemDrop().getItemStack());
        if (active() && (artifact || protectedPlayer(event.getPlayer()))) {
            event.setCancelled(true);
            if (managed(event.getPlayer())) {
                restoreItems(event.getPlayer());
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickup(EntityPickupItemEvent event) {
        if (event.getEntity() instanceof Player player) {
            if (active() && managedArtifact(event.getItem().getItemStack())) {
                event.setCancelled(true);
            } else {
                protect(event, player);
            }
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPickupArrow(PlayerPickupArrowEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSwap(PlayerSwapHandItemsEvent event) {
        if (active() && (managedArtifact(event.getMainHandItem()) || managedArtifact(event.getOffHandItem()))) {
            event.setCancelled(true);
        } else {
            protect(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onConsume(PlayerItemConsumeEvent event) {
        if (active() && managedArtifact(event.getItem())) {
            event.setCancelled(true);
        } else {
            protect(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onItemDamage(PlayerItemDamageEvent event) {
        if (active() && managedArtifact(event.getItem())) {
            event.setCancelled(true);
        } else {
            protect(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketEmpty(PlayerBucketEmptyEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBucketFill(PlayerBucketFillEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onArmorStand(PlayerArmorStandManipulateEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractEntity(PlayerInteractEntityEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInteractAtEntity(PlayerInteractAtEntityEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBreak(BlockBreakEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockPlace(BlockPlaceEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockDispense(BlockDispenseEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockFertilize(BlockFertilizeEvent event) {
        if (event.getPlayer() == null) {
            protect(event, event.getBlock().getWorld());
        } else {
            protect(event, event.getPlayer(), event.getBlock().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onCauldronLevelChange(CauldronLevelChangeEvent event) {
        if (event.getEntity() instanceof Player player) {
            protect(event, player, event.getBlock().getWorld());
        } else {
            protect(event, event.getBlock().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onTntPrime(TNTPrimeEvent event) {
        if (event.getPrimingEntity() instanceof Player player) {
            protect(event, player, event.getBlock().getWorld());
        } else {
            protect(event, event.getBlock().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockBurn(BlockBurnEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockFade(BlockFadeEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockForm(BlockFormEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityBlockForm(EntityBlockFormEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockGrow(BlockGrowEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onMoistureChange(MoistureChangeEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onSpongeAbsorb(SpongeAbsorbEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFluidFlow(BlockFromToEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onFluidLevelChange(FluidLevelChangeEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonExtend(BlockPistonExtendEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPistonRetract(BlockPistonRetractEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockIgnite(BlockIgniteEvent event) {
        if (event.getPlayer() == null) {
            protect(event, event.getBlock().getWorld());
        } else {
            protect(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockSpread(BlockSpreadEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeavesDecay(LeavesDecayEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onBlockExplode(BlockExplodeEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityExplode(EntityExplodeEvent event) {
        protect(event, event.getLocation().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityChangeBlock(EntityChangeBlockEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPlace(EntityPlaceEvent event) {
        if (event.getPlayer() == null) {
            protect(event, event.getEntity().getWorld());
        } else {
            protect(event, event.getPlayer(), event.getEntity().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityPortal(EntityPortalEvent event) {
        if (event.getEntity() instanceof Player player) {
            protect(event, player, event.getEntity().getWorld());
        } else {
            protect(event, event.getEntity().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingBreak(HangingBreakEvent event) {
        if (event instanceof HangingBreakByEntityEvent byEntity && byEntity.getRemover() instanceof Player player) {
            protect(event, player);
        } else {
            protect(event, event.getEntity().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onHangingPlace(HangingPlaceEvent event) {
        if (event.getPlayer() == null) {
            protect(event, event.getEntity().getWorld());
        } else {
            protect(event, event.getPlayer());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleDamage(VehicleDamageEvent event) {
        if (event.getAttacker() instanceof Player player) {
            protect(event, player, event.getVehicle().getWorld());
        } else {
            protect(event, event.getVehicle().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleDestroy(VehicleDestroyEvent event) {
        if (event.getAttacker() instanceof Player player) {
            protect(event, player, event.getVehicle().getWorld());
        } else {
            protect(event, event.getVehicle().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleCollision(VehicleEntityCollisionEvent event) {
        if (event.getEntity() instanceof Player player) {
            protect(event, player, event.getVehicle().getWorld());
        } else {
            protect(event, event.getVehicle().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onStructureGrow(StructureGrowEvent event) {
        if (event.getPlayer() == null) {
            protect(event, event.getWorld());
        } else {
            protect(event, event.getPlayer(), event.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onProjectileLaunch(ProjectileLaunchEvent event) {
        Projectile projectile = event.getEntity();
        if (projectile.getShooter() instanceof Player player) {
            protect(event, player);
        } else {
            protect(event, projectile.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPortalCreate(PortalCreateEvent event) {
        if (event.getEntity() instanceof Player player) {
            protect(event, player, event.getWorld());
        } else {
            protect(event, event.getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerPortal(PlayerPortalEvent event) {
        protect(event, event.getPlayer());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onLeash(PlayerLeashEntityEvent event) {
        protect(event, event.getPlayer(), event.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onShear(PlayerShearEntityEvent event) {
        protect(event, event.getPlayer(), event.getEntity().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onEntityInteract(EntityInteractEvent event) {
        protect(event, event.getBlock().getWorld());
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onVehicleEnter(VehicleEnterEvent event) {
        if (event.getEntered() instanceof Player player) {
            protect(event, player);
        } else {
            protect(event, event.getVehicle().getWorld());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onWeather(WeatherChangeEvent event) {
        Boolean configured = configuredStorm(event.getWorld());
        if (protectedWorld(event.getWorld()) && configured != null && configured != event.toWeatherState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onThunder(ThunderChangeEvent event) {
        Boolean configured = configuredThunder(event.getWorld());
        if (protectedWorld(event.getWorld()) && configured != null && configured != event.toThunderState()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();
        visibility.remove(playerId);
        occupiedPortals.remove(playerId);
        portalVisualizers.remove(playerId);
        menuSessions.remove(playerId);
        sidebars.remove(playerId);
        previousScoreboards.remove(playerId);
        portalSelections.remove(playerId);
        cooldowns.keySet().removeIf(key -> key.player().equals(playerId));
    }

    private void protect(Cancellable event, Player player) {
        if (protectedPlayer(player)) {
            event.setCancelled(true);
        }
    }

    private void protect(Cancellable event, Player player, World affectedWorld) {
        if (protectedWorld(affectedWorld) && !player.hasPermission(config.protection().bypassPermission())) {
            event.setCancelled(true);
        }
    }

    private void protect(Cancellable event, World world) {
        if (protectedWorld(world)) {
            event.setCancelled(true);
        }
    }

    private boolean protectedInventory(Inventory inventory) {
        Location location = inventory.getLocation();
        return location != null && protectedWorld(location.getWorld());
    }

    private void applyPlayer(Player player, boolean reset) {
        if (!active() || !player.isOnline() || !managed(player)) {
            return;
        }
        if (reset) {
            resetPlayer(player);
        } else {
            restoreItems(player);
        }
        visibility.putIfAbsent(player.getUniqueId(), config.visibility().defaultMode());
        updateSidebar(player);
    }

    private void invalidateMenuSessions() {
        List<MenuSession> sessions = List.copyOf(menuSessions.values());
        menuSessions.clear();
        for (MenuSession session : sessions) {
            Player player = Bukkit.getPlayer(session.player());
            if (player != null) {
                player.getScheduler().execute(plugin, () -> {
                    if (active() && player.getOpenInventory().getTopInventory() == session.inventory()) {
                        player.closeInventory();
                    }
                }, null, 1L);
            }
        }
    }

    private void cleanupRemovedWorlds(ManagedLobbyConfig previous, ManagedLobbyConfig next) {
        if (previous == null) {
            return;
        }
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (previous.manages(player.getWorld().getName()) && !next.manages(player.getWorld().getName())) {
                player.getScheduler().execute(plugin, () -> {
                    if (active()) {
                        leaveManagedPlayer(player);
                        scheduleVisibilityRefresh();
                    }
                }, null, 1L);
            }
        }
    }

    private void cleanupOwnedPlayer(Player player, boolean resetPresentation) {
        closeManagedMenu(player);
        removeManagedArtifacts(player, true);
        occupiedPortals.remove(player.getUniqueId());
        portalVisualizers.remove(player.getUniqueId());
        portalSelections.remove(player.getUniqueId());
        SidebarState sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar != null && player.getScoreboard() == sidebar.scoreboard()) {
            player.setScoreboard(previousScoreboards.getOrDefault(player.getUniqueId(),
                    Bukkit.getScoreboardManager().getMainScoreboard()));
        }
        previousScoreboards.remove(player.getUniqueId());
        if (visibility.remove(player.getUniqueId()) != null || resetPresentation) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                player.showPlayer(plugin, target);
            }
        }
    }

    private void closeManagedMenu(Player player) {
        MenuSession session = menuSessions.remove(player.getUniqueId());
        Inventory top = player.getOpenInventory().getTopInventory();
        if (managedMenuInventory(top) || session != null && top == session.inventory()) {
            player.closeInventory();
        }
    }

    private void removeManagedArtifacts(Player player, boolean onlyThisGeneration) {
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (onlyThisGeneration ? ownedArtifact(item) : managedArtifact(item)) {
                player.getInventory().setItem(slot, null);
            }
        }
    }

    private void restorePresentation(Player player) {
        SidebarState sidebar = sidebars.remove(player.getUniqueId());
        if (sidebar != null && player.getScoreboard() == sidebar.scoreboard()) {
            player.setScoreboard(previousScoreboards.getOrDefault(player.getUniqueId(),
                    Bukkit.getScoreboardManager().getMainScoreboard()));
        }
        previousScoreboards.remove(player.getUniqueId());
        if (visibility.remove(player.getUniqueId()) != null) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                player.showPlayer(plugin, target);
            }
        }
    }

    private void resetPlayer(Player player) {
        player.closeInventory();
        player.setGameMode(GameMode.ADVENTURE);
        player.setAllowFlight(false);
        player.setFlying(false);
        player.setFlySpeed(0.1F);
        player.setWalkSpeed(0.2F);
        player.setFireTicks(0);
        player.setFallDistance(0);
        player.setNoDamageTicks(20);
        player.setFoodLevel(20);
        player.setSaturation(5.0F);
        player.setExhaustion(0.0F);
        player.setLevel(0);
        player.setExp(0.0F);
        player.setTotalExperience(0);
        player.setVelocity(new org.bukkit.util.Vector());
        for (PotionEffect effect : player.getActivePotionEffects()) {
            player.removePotionEffect(effect.getType());
        }
        AttributeInstance maximum = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maximum == null ? 20.0 : maximum.getValue());
        player.getInventory().clear();
        player.getInventory().setArmorContents(new ItemStack[4]);
        player.getInventory().setItemInOffHand(new ItemStack(Material.AIR));
        restoreItems(player);
    }

    private void restoreItems(Player player) {
        ManagedLobbyConfig current = config;
        if (current == null || !managed(player)) {
            return;
        }
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack existing = player.getInventory().getItem(slot);
            if (managedItemId(existing) != null) {
                player.getInventory().setItem(slot, null);
            }
        }
        current.items().values().forEach(item -> player.getInventory().setItem(item.slot(), item(item, null)));
    }

    private void restoreItemDelayed(Player player, ManagedLobbyConfig.LobbyItem item) {
        player.getScheduler().runDelayed(plugin, ignored -> {
            if (active() && player.isOnline() && managed(player) && config.items().containsKey(item.id())) {
                player.getInventory().setItem(item.slot(), item(item, null));
            }
        }, null, 1L);
    }

    private ItemStack item(ManagedLobbyConfig.LobbyItem item, UUID menuToken) {
        return item(item.material(), item.amount(), item.name(), item.lore(), item.id(), menuToken);
    }

    private ItemStack item(ManagedLobbyConfig.MenuSlot item, UUID menuToken) {
        return item(item.material(), item.amount(), item.name(), item.lore(), null, menuToken);
    }

    private ItemStack item(String materialName, int amount, String name, List<String> lore,
            String managedId, UUID menuToken) {
        Material material = requireMaterial(materialName);
        if (amount > material.getMaxStackSize()) {
            throw new IllegalArgumentException("item amount exceeds material stack size: " + materialName);
        }
        ItemStack result = new ItemStack(material, amount);
        ItemMeta meta = Objects.requireNonNull(result.getItemMeta(), "item material has no metadata");
        if (!name.isEmpty()) {
            meta.displayName(component(name));
        }
        meta.lore(lore.stream().map(this::component).toList());
        if (managedId != null) {
            meta.getPersistentDataContainer().set(itemKey, PersistentDataType.STRING, managedId);
        }
        meta.getPersistentDataContainer().set(generationKey, PersistentDataType.STRING, generationId.toString());
        if (menuToken != null) {
            meta.getPersistentDataContainer().set(menuSessionKey, PersistentDataType.STRING, menuToken.toString());
        }
        if (!result.setItemMeta(meta)) {
            throw new IllegalArgumentException("item metadata is invalid for material: " + materialName);
        }
        return result;
    }

    private Map<String, Object> givePortalWand(Player player) {
        if (!portalEditor(player)) {
            return failure("unavailable", "player lacks the portal editor permission");
        }
        ItemStack wand = new ItemStack(Material.BLAZE_ROD);
        ItemMeta meta = Objects.requireNonNull(wand.getItemMeta(), "portal wand has no metadata");
        meta.displayName(component("<italic:false><gradient:#38D9FF:#4F7CFF:#A855F7>"
                + "<bold>◆ Editor de portales</bold></gradient>"));
        meta.lore(List.of(
                component("<italic:false><#A8B3C7>Selecciona los límites del portal.</#A8B3C7>"),
                component("<italic:false><#303746>▸</#303746> <#F8FAFC>Izquierdo: posición 1</#F8FAFC>"),
                component("<italic:false><#303746>▸</#303746> <#F8FAFC>Derecho: posición 2</#F8FAFC>")));
        meta.getPersistentDataContainer().set(portalWandKey, PersistentDataType.BYTE, (byte) 1);
        meta.getPersistentDataContainer().set(generationKey, PersistentDataType.STRING, generationId.toString());
        if (!wand.setItemMeta(meta)) {
            return failure("error", "unable to create portal editor wand");
        }
        if (!player.getInventory().addItem(wand).isEmpty()) {
            return failure("unavailable", "player inventory has no room for the portal wand");
        }
        return success("portal-wand", Map.of("message", "Varita de portales entregada."));
    }

    private boolean portalWand(ItemStack item) {
        return ownedArtifact(item) && item.getItemMeta().getPersistentDataContainer()
                .has(portalWandKey, PersistentDataType.BYTE);
    }

    private void captureWandPosition(PlayerInteractEvent event) {
        org.bukkit.block.Block clicked = event.getClickedBlock();
        if (!portalEditor(event.getPlayer()) || clicked == null) {
            return;
        }
        if (event.getAction() != org.bukkit.event.block.Action.LEFT_CLICK_BLOCK
                && event.getAction() != org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        boolean first = event.getAction() == org.bukkit.event.block.Action.LEFT_CLICK_BLOCK;
        event.setCancelled(true);
        PortalPosition position = new PortalPosition(clicked.getWorld().getName(),
                clicked.getX(), clicked.getY(), clicked.getZ());
        UUID playerId = event.getPlayer().getUniqueId();
        portalSelections.compute(playerId, (ignored, selection) -> first
                ? new PortalSelection(position, selection == null ? null : selection.second())
                : new PortalSelection(selection == null ? null : selection.first(), position));
        feedback(event.getPlayer(), first ? "portal-pos1" : "portal-pos2", Map.of(
                "%world%", position.world(), "%x%", Integer.toString(position.x()),
                "%y%", Integer.toString(position.y()), "%z%", Integer.toString(position.z())),
                first ? "<green>Primera posición guardada.</green>"
                        : "<green>Segunda posición guardada.</green>");
    }

    private boolean openMenu(Player player, String id) {
        ManagedLobbyConfig.Menu menu = config.menus().get(id);
        if (menu == null) {
            return false;
        }
        UUID token = UUID.randomUUID();
        MenuHolder holder = new MenuHolder(player.getUniqueId(), generationId, token);
        Inventory inventory = Bukkit.createInventory(holder, menu.rows() * 9, component(menu.title()));
        holder.inventory = inventory;
        menu.slots().forEach((slot, definition) -> inventory.setItem(slot, item(definition, token)));
        MenuSession session = new MenuSession(player.getUniqueId(), token, menu, inventory);
        menuSessions.put(player.getUniqueId(), session);
        player.openInventory(inventory);
        return true;
    }

    private MenuSession menuSession(Inventory inventory, Player player) {
        if (!(inventory.getHolder(false) instanceof MenuHolder holder)
                || !holder.owner.equals(player.getUniqueId()) || !holder.generation.equals(generationId)) {
            return null;
        }
        MenuSession session = menuSessions.get(player.getUniqueId());
        return session != null && session.inventory() == inventory && session.token().equals(holder.token)
                ? session : null;
    }

    boolean managedMenuInventory(Inventory inventory) {
        return inventory.getHolder(false) instanceof MenuHolder;
    }

    private boolean executeAction(Player player, ManagedLobbyConfig.Action action) {
        return switch (action.type()) {
            case NONE -> false;
            case SPAWN -> {
                Location target = spawn();
                if (target != null) {
                    player.teleportAsync(target);
                    feedback(player, "spawn-solicitado", Map.of(),
                            "<#A8B3C7>Regresando al punto de aparición...</#A8B3C7>");
                    yield true;
                }
                feedback(player, "spawn-no-configurado", Map.of(),
                        "<#FFB347>El punto de aparición no está configurado.</#FFB347>");
                yield false;
            }
            case MENU -> {
                player.closeInventory();
                player.getScheduler().runDelayed(plugin, ignored -> {
                    if (!closed.get() && active() && managed(player)) {
                        if (openMenu(player, action.target())) {
                            feedback(player, "menu-abierto", Map.of("%menu%", action.target()),
                                    "<#A8B3C7>Menú abierto.</#A8B3C7>");
                        } else {
                            feedback(player, "menu-no-disponible", Map.of("%menu%", action.target()),
                                    "<#FF5C7A>El menú no está disponible.</#FF5C7A>");
                        }
                    }
                }, null, 1L);
                yield true;
            }
            case VISIBILITY -> {
                ManagedLobbyConfig.VisibilityMode mode = requestedVisibility(player, action.target());
                visibility.put(player.getUniqueId(), mode);
                scheduleVisibilityRefresh();
                String message = switch (mode) {
                    case ALL -> "visibilidad-todos";
                    case STAFF -> "visibilidad-personal";
                    case NONE, CYCLE -> "visibilidad-ninguno";
                };
                feedback(player, message, Map.of(), "<#55FF88>Visibilidad actualizada.</#55FF88>");
                yield true;
            }
            case CONNECT -> connect(player, action.target());
            case TITLE -> {
                showTitle(player, action.target());
                yield true;
            }
            case SOUND -> {
                playSound(player, action.target());
                yield true;
            }
            case PARTICLE -> {
                showParticle(player, action.target());
                yield true;
            }
            default -> throw new IllegalStateException("unknown native action: " + action.type());
        };
    }

    private boolean queuePortalAction(Player player, ManagedLobbyPortalIndex.Portal expected,
            PortalOccupancy transition) {
        CompletableFuture<Void> result;
        try {
            result = pendingFuture();
        } catch (RuntimeException exception) {
            return false;
        }
        final boolean accepted;
        try {
            accepted = player.getScheduler().execute(plugin, () -> {
                try {
                    if (!result.isDone() && portalActionStillValid(player, expected, transition)) {
                        CooldownKey key = new CooldownKey(player.getUniqueId(), "portal:" + expected.id());
                        long now = System.currentTimeMillis();
                        long readyAt = cooldowns.getOrDefault(key, 0L);
                        if (readyAt > now) {
                            cooldownFeedback(player, "portal-cooldown", readyAt - now);
                        } else if (executeAction(player, expected.action())) {
                            cooldowns.put(key, now + expected.cooldownMillis());
                        }
                    }
                    result.complete(null);
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                }
            }, () -> result.completeExceptionally(new BridgeClosedException()), 1L);
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
            return false;
        }
        if (!accepted) {
            result.completeExceptionally(new BridgeClosedException());
            return false;
        }
        return true;
    }

    private boolean portalActionStillValid(Player player, ManagedLobbyPortalIndex.Portal expected,
            PortalOccupancy transition) {
        if (closed.get() || !active() || !player.isOnline() || !managed(player)) {
            return false;
        }
        ManagedLobbyPortalIndex.Portal current = portal(expected.id());
        Location location = player.getLocation();
        ManagedLobbyPortalIndex.Portal trigger = portalIndex.highest(location.getWorld().getName(),
                location.getX(), location.getY(), location.getZ());
        boolean permitted = current != null && (current.permission() == null
                || player.hasPermission(current.permission()));
        return portalActionStillValid(expected, current, transition, occupiedPortals.get(player.getUniqueId()),
                location.getWorld().getName(), location.getX(), location.getY(), location.getZ(), permitted,
                trigger != null && current != null && trigger.id().equals(current.id()));
    }

    static boolean portalActionStillValid(ManagedLobbyPortalIndex.Portal expected,
            ManagedLobbyPortalIndex.Portal current, PortalOccupancy expectedOccupancy,
            PortalOccupancy currentOccupancy, String world,
            double x, double y, double z, boolean permitted, boolean currentTrigger) {
        return current != null && current.enabled() && current.id().equals(expected.id())
                && current.action().equals(expected.action()) && expectedOccupancy.equals(currentOccupancy)
                && current.id().equals(currentOccupancy.portal())
                && current.world().equals(world) && current.contains(x, y, z) && permitted && currentTrigger;
    }

    static boolean itemTrigger(org.bukkit.event.block.Action action) {
        return action == org.bukkit.event.block.Action.RIGHT_CLICK_AIR
                || action == org.bukkit.event.block.Action.RIGHT_CLICK_BLOCK;
    }

    private <T> CompletableFuture<T> pendingFuture() {
        synchronized (lifecycleLock) {
            requireOpen();
            int count = pendingActions.incrementAndGet();
            if (count > maximumPendingActions) {
                pendingActions.decrementAndGet();
                throw new OverloadedException();
            }
            CompletableFuture<T> result = new CompletableFuture<>();
            pendingFutures.add(result);
            result.whenComplete((ignored, failure) -> {
                if (pendingFutures.remove(result)) {
                    pendingActions.decrementAndGet();
                }
            });
            return result;
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new BridgeClosedException();
        }
    }

    private static void cancel(ScheduledTask task) {
        if (task != null) {
            task.cancel();
        }
    }

    private boolean connect(Player player, String server) {
        ManagedLobbyConfig.Server configured = config.transfers().enabled(server);
        if (configured == null) {
            feedback(player, "servidor-no-disponible", Map.of("%server%", server),
                    "<#FF5C7A>El destino no está disponible.</#FF5C7A>");
            return false;
        }
        CooldownKey key = new CooldownKey(player.getUniqueId(), "transfer");
        long now = System.currentTimeMillis();
        long readyAt = cooldowns.getOrDefault(key, 0L);
        if (readyAt > now) {
            feedback(player, "transferencia-espera",
                    Map.of("%seconds%", Long.toString(secondsRemaining(readyAt - now))),
                    "<#FFB347>Espera antes de cambiar de servidor.</#FFB347>");
            return false;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream output = new DataOutputStream(bytes)) {
                output.writeUTF("Connect");
                output.writeUTF(configured.target());
            }
            player.sendPluginMessage(plugin, BUNGEE_CHANNEL, bytes.toByteArray());
            cooldowns.put(key, now + config.transfers().cooldownMillis());
            feedback(player, "transferencia-iniciada", Map.of("%server%", server),
                    "<#A8B3C7>Solicitando conexión...</#A8B3C7>");
            return true;
        } catch (IOException exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to encode BungeeCord Connect request", exception);
            feedback(player, "servidor-no-disponible", Map.of("%server%", server),
                    "<#FF5C7A>No se pudo solicitar la conexión.</#FF5C7A>");
            return false;
        }
    }

    private void showTitle(Player player, String id) {
        ManagedLobbyConfig.LobbyTitle configured = config.titles().get(id);
        if (configured != null) {
            player.showTitle(Title.title(component(placeholders(configured.title(), player)),
                    component(placeholders(configured.subtitle(), player)),
                    Title.Times.times(Duration.ofMillis(configured.fadeInTicks() * 50L),
                            Duration.ofMillis(configured.stayTicks() * 50L),
                            Duration.ofMillis(configured.fadeOutTicks() * 50L))));
        }
    }

    private void playSound(Player player, String id) {
        ManagedLobbyConfig.LobbySound configured = config.sounds().get(id);
        if (configured != null) {
            player.playSound(player.getLocation(), nativeSound(configured.sound()), SoundCategory.MASTER,
                    configured.volume(), configured.pitch());
        }
    }

    private void showParticle(Player player, String id) {
        ManagedLobbyConfig.LobbyParticle configured = config.particles().get(id);
        if (configured != null) {
            player.spawnParticle(nativeParticle(configured.particle()), player.getLocation(), configured.count(),
                    configured.offsetX(), configured.offsetY(), configured.offsetZ(), configured.speed());
        }
    }

    private void showWelcome(Player player) {
        ManagedLobbyConfig.Join join = config.join();
        if (join.welcomeMessage() != null) {
            String message = config.messages().get(join.welcomeMessage());
            if (message != null) {
                player.sendMessage(component(placeholders(message, player)));
            }
        }
        if (join.welcomeTitle() != null) {
            showTitle(player, join.welcomeTitle());
        }
        if (join.welcomeSound() != null) {
            playSound(player, join.welcomeSound());
        }
        if (join.welcomeParticle() != null) {
            showParticle(player, join.welcomeParticle());
        }
    }

    private void cooldownFeedback(Player player, String key, long remainingMillis) {
        feedback(player, key, Map.of("%seconds%", Long.toString(secondsRemaining(remainingMillis))),
                "<#FFB347>Espera %seconds% s antes de volver a usarlo.</#FFB347>");
    }

    private void feedback(Player player, String key, Map<String, String> replacements, String fallback) {
        String source = config.messages().getOrDefault(key, fallback);
        source = source.replace("%prefix%", config.messages().getOrDefault("prefix", ""));
        for (Map.Entry<String, String> replacement : replacements.entrySet()) {
            source = source.replace(replacement.getKey(), replacement.getValue());
        }
        player.sendMessage(component(placeholders(source, player)));
    }

    static long secondsRemaining(long milliseconds) {
        return Math.max(1L, (milliseconds + 999L) / 1_000L);
    }

    private void scheduleVisibilityRefresh() {
        scheduleVisibilityRefresh(() -> true, false);
    }

    private void scheduleVisibilityRefresh(BooleanSupplier ready, boolean requireAccepted) {
        for (Player viewer : Bukkit.getOnlinePlayers()) {
            if (managed(viewer) || visibility.containsKey(viewer.getUniqueId())) {
                boolean accepted = viewer.getScheduler().execute(plugin, () -> {
                    if (!ready.getAsBoolean() || !active()) {
                        return;
                    }
                    if (managed(viewer)) {
                        applyVisibility(viewer);
                    } else {
                        leaveManagedPlayer(viewer);
                    }
                }, null, 1L);
                if (!accepted && requireAccepted) {
                    throw new IllegalStateException("managed lobby visibility scheduling was rejected");
                }
            }
        }
    }

    private void applyVisibility(Player viewer) {
        ManagedLobbyConfig.VisibilityMode mode = visibility.getOrDefault(viewer.getUniqueId(),
                config.visibility().defaultMode());
        for (Player target : Bukkit.getOnlinePlayers()) {
            boolean shown = viewer.equals(target) || !managed(target) || mode == ManagedLobbyConfig.VisibilityMode.ALL
                    || mode == ManagedLobbyConfig.VisibilityMode.STAFF
                    && target.hasPermission(config.visibility().staffPermission());
            if (shown) {
                viewer.showPlayer(plugin, target);
            } else {
                viewer.hidePlayer(plugin, target);
            }
        }
    }

    private void leaveManagedPlayer(Player player) {
        closeManagedMenu(player);
        removeManagedArtifacts(player, false);
        occupiedPortals.remove(player.getUniqueId());
        portalVisualizers.remove(player.getUniqueId());
        portalSelections.remove(player.getUniqueId());
        restorePresentation(player);
    }

    private ManagedLobbyConfig.VisibilityMode requestedVisibility(Player player, String requested) {
        ManagedLobbyConfig.VisibilityMode mode = ManagedLobbyConfig.VisibilityMode.valueOf(
                requested.toUpperCase(Locale.ROOT));
        if (mode != ManagedLobbyConfig.VisibilityMode.CYCLE) {
            return mode;
        }
        ManagedLobbyConfig.VisibilityMode current = visibility.getOrDefault(player.getUniqueId(),
                config.visibility().defaultMode());
        return switch (current) {
            case ALL -> ManagedLobbyConfig.VisibilityMode.STAFF;
            case STAFF -> ManagedLobbyConfig.VisibilityMode.NONE;
            case NONE, CYCLE -> ManagedLobbyConfig.VisibilityMode.ALL;
        };
    }

    private void updateSidebar(Player player) {
        ManagedLobbyConfig.Sidebar configured = config.sidebar();
        if (!configured.enabled() || !managed(player)) {
            SidebarState previous = sidebars.remove(player.getUniqueId());
            if (previous != null && player.getScoreboard() == previous.scoreboard()) {
                player.setScoreboard(previousScoreboards.getOrDefault(player.getUniqueId(),
                        Bukkit.getScoreboardManager().getMainScoreboard()));
            }
            previousScoreboards.remove(player.getUniqueId());
            return;
        }
        SidebarState state = sidebars.get(player.getUniqueId());
        if (state == null || state.lines().size() != configured.lines().size()) {
            previousScoreboards.putIfAbsent(player.getUniqueId(), player.getScoreboard());
            state = createSidebar(configured);
            sidebars.put(player.getUniqueId(), state);
            player.setScoreboard(state.scoreboard());
        } else if (scoreboardNeedsReclaim(player.getScoreboard(), state.scoreboard())) {
            previousScoreboards.put(player.getUniqueId(), player.getScoreboard());
            player.setScoreboard(state.scoreboard());
        }
        String titleFrame = configured.titleFrames().get(Math.floorMod(scoreboardFrame.get(),
                configured.titleFrames().size()));
        Component title = component(placeholders(titleFrame, player));
        if (!title.equals(state.title())) {
            state.objective().displayName(title);
            state.title = title;
        }
        for (int index = 0; index < configured.lines().size(); index++) {
            Component line = component(placeholders(configured.lines().get(index), player));
            if (!line.equals(state.lines().get(index))) {
                state.teams().get(index).prefix(line);
                state.lines().set(index, line);
            }
        }
    }

    static boolean scoreboardNeedsReclaim(Scoreboard current, Scoreboard managed) {
        return current != managed;
    }

    private SidebarState createSidebar(ManagedLobbyConfig.Sidebar configured) {
        Scoreboard scoreboard = Bukkit.getScoreboardManager().getNewScoreboard();
        Component title = component(configured.titleFrames().getFirst());
        Objective objective = scoreboard.registerNewObjective("shalobby", Criteria.DUMMY, title);
        objective.setDisplaySlot(DisplaySlot.SIDEBAR);
        List<Team> teams = new ArrayList<>();
        List<Component> lines = new ArrayList<>();
        for (int index = 0; index < configured.lines().size(); index++) {
            String entry = "\u00a7" + Integer.toHexString(index);
            Team team = scoreboard.registerNewTeam("line" + index);
            team.addEntry(entry);
            objective.getScore(entry).setScore(configured.lines().size() - index);
            teams.add(team);
            lines.add(Component.empty());
        }
        return new SidebarState(scoreboard, objective, teams, lines, title);
    }

    private String placeholders(String source, Player player) {
        Location location = player.getLocation();
        return source.replace("%player%", player.getName())
                .replace("%online%", Integer.toString(Bukkit.getOnlinePlayers().size()))
                .replace("%world%", location.getWorld().getName())
                .replace("%x%", Integer.toString(location.getBlockX()))
                .replace("%y%", Integer.toString(location.getBlockY()))
                .replace("%z%", Integer.toString(location.getBlockZ()))
                .replace("%ping%", Integer.toString(player.getPing()))
                .replace("%visibility%", visibility.getOrDefault(player.getUniqueId(),
                        config.visibility().defaultMode()).name().toLowerCase(Locale.ROOT));
    }

    private void visualizePortals(Player player) {
        boolean explicit = portalVisualizers.contains(player.getUniqueId());
        if (!explicit && !player.hasPermission(config.visibility().staffPermission())) {
            return;
        }
        Location origin = player.getLocation();
        for (ManagedLobbyPortalIndex.Portal portal : config.portals()) {
            if ((!explicit && !portal.visualize()) || !origin.getWorld().getName().equals(portal.world())
                    || distanceSquared(origin, portal) > 96.0 * 96.0) {
                continue;
            }
            Particle particle = Particle.END_ROD;
            double[] xs = {portal.minimumX(), portal.maximumX()};
            double[] ys = {portal.minimumY(), portal.maximumY()};
            double[] zs = {portal.minimumZ(), portal.maximumZ()};
            for (double x : xs) {
                for (double y : ys) {
                    for (double z : zs) {
                        player.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0);
                    }
                }
            }
        }
    }

    private static double distanceSquared(Location origin, ManagedLobbyPortalIndex.Portal portal) {
        double centerX = (portal.minimumX() + portal.maximumX()) / 2.0;
        double centerY = (portal.minimumY() + portal.maximumY()) / 2.0;
        double centerZ = (portal.minimumZ() + portal.maximumZ()) / 2.0;
        return square(origin.getX() - centerX) + square(origin.getY() - centerY) + square(origin.getZ() - centerZ);
    }

    private static double square(double value) {
        return value * value;
    }

    private Location spawn() {
        ManagedLobbyConfig current = config;
        if (current == null || current.spawn() == null) {
            return null;
        }
        ManagedLobbyConfig.Spawn spawn = current.spawn();
        World world = Bukkit.getWorld(spawn.world());
        return world == null ? null : new Location(world, spawn.x(), spawn.y(), spawn.z(), spawn.yaw(), spawn.pitch());
    }

    private boolean managed(Player player) {
        return managed(player.getWorld());
    }

    private boolean managed(World world) {
        ManagedLobbyConfig current = config;
        return current != null && current.manages(world.getName());
    }

    private boolean protectedPlayer(Player player) {
        return active() && managed(player) && config.protection().enabled()
                && !player.hasPermission(config.protection().bypassPermission());
    }

    private boolean protectedWorld(World world) {
        return active() && managed(world) && config.protection().enabled();
    }

    private boolean portalEditor(Player player) {
        return active() && managed(player) && player.hasPermission(config.protection().bypassPermission());
    }

    private Boolean configuredStorm(World world) {
        return config.worlds().stream().filter(settings -> settings.name().equals(world.getName()))
                .map(ManagedLobbyConfig.ManagedWorld::storm).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private Boolean configuredThunder(World world) {
        return config.worlds().stream().filter(settings -> settings.name().equals(world.getName()))
                .map(ManagedLobbyConfig.ManagedWorld::thundering).filter(Objects::nonNull).findFirst().orElse(null);
    }

    private static PortalPosition portalPosition(Location location) {
        return new PortalPosition(location.getWorld().getName(), location.getBlockX(), location.getBlockY(),
                location.getBlockZ());
    }

    private String managedItemId(ItemStack item) {
        return !ownedArtifact(item) ? null
                : item.getItemMeta().getPersistentDataContainer().get(itemKey, PersistentDataType.STRING);
    }

    private String menuToken(ItemStack item) {
        return !ownedArtifact(item) ? null
                : item.getItemMeta().getPersistentDataContainer().get(menuSessionKey, PersistentDataType.STRING);
    }

    private boolean managedArtifact(ItemStack item) {
        return item != null && item.hasItemMeta() && item.getItemMeta().getPersistentDataContainer()
                .has(generationKey, PersistentDataType.STRING);
    }

    private boolean ownedArtifact(ItemStack item) {
        if (!managedArtifact(item)) {
            return false;
        }
        String generation = item.getItemMeta().getPersistentDataContainer().get(
                generationKey, PersistentDataType.STRING);
        return generationId.toString().equals(generation);
    }

    private Component component(String source) {
        return MINI_MESSAGE.deserialize(source).decoration(TextDecoration.ITALIC, TextDecoration.State.FALSE);
    }

    private static Material requireMaterial(String value) {
        Material material = Material.matchMaterial(value);
        if (material == null || !material.isItem() || material.isAir()) {
            throw new IllegalArgumentException("unknown or non-item material: " + value);
        }
        return material;
    }

    @SuppressWarnings("removal")
    private static Sound nativeSound(String value) {
        return Registry.SOUNDS.stream().filter(sound -> registryName(sound.key().value()).equals(value)
                || sound.key().asString().equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown sound: " + value));
    }

    private static Particle nativeParticle(String value) {
        return Registry.PARTICLE_TYPE.stream().filter(particle -> registryName(particle.getKey()).equals(value)
                || particle.getKey().asString().equalsIgnoreCase(value)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("unknown particle: " + value));
    }

    private static String registryName(NamespacedKey key) {
        return registryName(key.getKey());
    }

    private static String registryName(String key) {
        return key.replace('.', '_').replace('/', '_').toUpperCase(Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private static void applyGameRule(World world, String name, String value) {
        GameRule<?> rule = GameRule.getByName(name);
        if (rule == null) {
            throw new IllegalArgumentException("unknown game rule: " + name);
        }
        if (rule.getType() == Boolean.class) {
            world.setGameRule((GameRule<Boolean>) rule, gameRuleBoolean(name, value));
        } else if (rule.getType() == Integer.class) {
            world.setGameRule((GameRule<Integer>) rule, gameRuleInteger(name, value));
        } else {
            throw new IllegalArgumentException("unsupported game rule type: " + name);
        }
    }

    private static void validateGameRule(String name, String value) {
        GameRule<?> rule = GameRule.getByName(name);
        if (rule == null) {
            throw new IllegalArgumentException("unknown game rule: " + name);
        }
        if (rule.getType() == Boolean.class) {
            gameRuleBoolean(name, value);
        } else if (rule.getType() == Integer.class) {
            gameRuleInteger(name, value);
        } else {
            throw new IllegalArgumentException("unsupported game rule type: " + name);
        }
    }

    private static boolean gameRuleBoolean(String name, String value) {
        if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
            throw new IllegalArgumentException("game rule " + name + " requires a boolean value");
        }
        return Boolean.parseBoolean(value);
    }

    private static int gameRuleInteger(String name, String value) {
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("game rule " + name + " requires an integer value", exception);
        }
    }

    private boolean active() {
        return !closed.get() && config != null && coordinator.isActive(this);
    }

    void reactivate() {
        if (closed.get() || config == null) {
            return;
        }
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, this::reactivateNative);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Unable to reactivate the previous managed lobby generation",
                    exception);
        }
    }

    void reactivateNative() {
        if (!active()) {
            return;
        }
        applyWorlds();
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.getScheduler().execute(plugin, () -> {
                if (active()) {
                    applyPlayer(player, false);
                }
            }, null, 1L);
        }
        scheduleVisibilityRefresh();
    }

    private Map<String, Object> status() {
        ManagedLobbyConfig current = config;
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("generation", generationId.toString());
        details.put("active", active());
        details.put("invocationAdmissionOpen", invocations.snapshot().accepting());
        details.put("pendingActions", pendingActions.get());
        details.put("maximumPendingActions", maximumPendingActions);
        details.put("directory", store.directory().toString());
        details.put("files", ManagedLobbyStore.FILES);
        if (current != null) {
            details.put("spawnConfigured", current.spawn() != null);
            details.put("items", current.items().size());
            details.put("menus", current.menus().size());
            details.put("servers", current.transfers().servers().size());
            details.put("portals", current.portals().size());
        }
        return success(current == null ? "uninitialized" : active() ? "ready" : "standby", details);
    }

    private <T> CompletionStage<T> submitFile(CheckedSupplier<T> action) {
        CompletableFuture<T> result;
        try {
            result = pendingFuture();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        try {
            fileExecutor.execute(() -> {
                try {
                    if (result.isDone() || closed.get()) {
                        result.completeExceptionally(new BridgeClosedException());
                        return;
                    }
                    T value = action.get();
                    result.complete(value);
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RejectedExecutionException exception) {
            result.completeExceptionally(new OverloadedException());
        }
        return result;
    }

    private <T> CompletionStage<T> scheduleGlobal(Supplier<T> action) {
        CompletableFuture<T> result;
        try {
            result = pendingFuture();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                try {
                    if (result.isDone() || closed.get()) {
                        result.completeExceptionally(new BridgeClosedException());
                        return;
                    }
                    result.complete(action.get());
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private CompletionStage<Map<String, Object>> withPlayer(UUID playerId,
            Function<Player, Map<String, Object>> action) {
        return withPlayerValue(playerId, action).thenApply(result -> result == null
                ? failure("unknown", "player is not online") : result);
    }

    private <T> CompletionStage<T> withPlayerValue(UUID playerId, Function<Player, T> action) {
        CompletableFuture<T> result;
        try {
            result = pendingFuture();
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                if (result.isDone() || closed.get()) {
                    result.completeExceptionally(new BridgeClosedException());
                    return;
                }
                Player player = Bukkit.getPlayer(playerId);
                if (player == null || !player.isOnline()) {
                    result.complete(null);
                    return;
                }
                boolean accepted = player.getScheduler().execute(plugin, () -> {
                    try {
                        if (result.isDone() || closed.get()) {
                            result.completeExceptionally(new BridgeClosedException());
                            return;
                        }
                        result.complete(action.apply(player));
                    } catch (Throwable failure) {
                        result.completeExceptionally(failure);
                    }
                }, () -> result.completeExceptionally(new BridgeClosedException()), 1L);
                if (!accepted) {
                    result.completeExceptionally(new BridgeClosedException());
                }
            });
        } catch (RuntimeException exception) {
            result.completeExceptionally(exception);
        }
        return result;
    }

    private static CompletionStage<Map<String, Object>> explicit(CompletionStage<Map<String, Object>> stage) {
        return stage.handle((result, failure) -> failure == null ? result
                : failure(classify(unwrap(failure)), message(unwrap(failure))));
    }

    private static Map<String, Object> success(String state, Map<String, ?> details) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("ok", true);
        result.put("state", state);
        result.putAll(details);
        return java.util.Collections.unmodifiableMap(result);
    }

    private static Map<String, Object> failure(String state, String error) {
        return Map.of("ok", false, "state", state, "error", bounded(error));
    }

    private static String classify(Throwable failure) {
        if (failure instanceof OverloadedException || failure instanceof RejectedExecutionException) {
            return "overloaded";
        }
        if (failure instanceof UnknownResourceException) {
            return "unknown";
        }
        if (failure instanceof IllegalArgumentException) {
            return "invalid";
        }
        if (failure instanceof IllegalStateException) {
            return "unavailable";
        }
        return "error";
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static String message(Throwable failure) {
        return failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
    }

    private static String bounded(String value) {
        String result = String.valueOf(value);
        return result.length() <= MAX_ERROR_TEXT ? result : result.substring(0, MAX_ERROR_TEXT);
    }

    private static boolean sameBlock(Location first, Location second) {
        return first.getWorld().equals(second.getWorld()) && first.getBlockX() == second.getBlockX()
                && first.getBlockY() == second.getBlockY() && first.getBlockZ() == second.getBlockZ();
    }

    @Override
    public void close() {
        final boolean wasActive;
        final PaperManagedLobbyBridge fallback;
        final Map<UUID, MenuSession> closingMenus;
        final Map<UUID, SidebarState> closingSidebars;
        final Map<UUID, Scoreboard> closingPreviousScoreboards;
        final List<CompletableFuture<?>> closingFutures;
        synchronized (lifecycleLock) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            cancel(enforcementTask);
            cancel(scoreboardTask);
            cancel(activationTask);
            if (registered.compareAndSet(true, false)) {
                HandlerList.unregisterAll(this);
            }
            enforcementTask = null;
            scoreboardTask = null;
            activationTask = null;
            wasActive = coordinator.isActive(this);
            closingMenus = Map.copyOf(menuSessions);
            closingSidebars = Map.copyOf(sidebars);
            closingPreviousScoreboards = Map.copyOf(previousScoreboards);
            fallback = coordinator.deactivate(this);
            closingFutures = List.copyOf(pendingFutures);
        }
        BridgeClosedException closedFailure = new BridgeClosedException();
        closingFutures.forEach(future -> future.completeExceptionally(closedFailure));
        fileExecutor.shutdownNow();
        try {
            plugin.getServer().getGlobalRegionScheduler().execute(plugin, () -> {
                for (Player player : Bukkit.getOnlinePlayers()) {
                    player.getScheduler().execute(plugin,
                            () -> cleanupPlayer(player, closingMenus.get(player.getUniqueId()),
                                    closingSidebars.get(player.getUniqueId()),
                                    closingPreviousScoreboards.get(player.getUniqueId()), fallback == null), null, 1L);
                }
            });
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.FINE, "Managed lobby player cleanup could not be scheduled", exception);
        }
        if (wasActive && fallback != null) {
            fallback.reactivate();
        }
        visibility.clear();
        cooldowns.clear();
        occupiedPortals.clear();
        portalVisualizers.clear();
        menuSessions.clear();
        sidebars.clear();
        previousScoreboards.clear();
        portalSelections.clear();
    }

    private void cleanupPlayer(Player player, MenuSession menu, SidebarState sidebar, Scoreboard previousScoreboard,
            boolean resetPresentation) {
        if (menu != null && player.getOpenInventory().getTopInventory() == menu.inventory()) {
            player.closeInventory();
        }
        if (sidebar != null && player.getScoreboard() == sidebar.scoreboard()) {
            player.setScoreboard(previousScoreboard == null
                    ? Bukkit.getScoreboardManager().getMainScoreboard() : previousScoreboard);
        }
        for (int slot = 0; slot < player.getInventory().getSize(); slot++) {
            ItemStack item = player.getInventory().getItem(slot);
            if (ownedArtifact(item)) {
                player.getInventory().setItem(slot, null);
            }
        }
        if (resetPresentation) {
            for (Player target : Bukkit.getOnlinePlayers()) {
                player.showPlayer(plugin, target);
            }
        }
    }

    @FunctionalInterface
    private interface CheckedSupplier<T> {
        T get() throws Exception;
    }

    @FunctionalInterface
    private interface PortalMutation {
        ManagedLobbyPortalIndex.Portal apply(ManagedLobbyConfig current,
                ManagedLobbyPortalIndex.Portal existing);
    }

    private record CooldownKey(UUID player, String action) {
    }

    record PreparedConfig(ManagedLobbyConfig config, ManagedLobbyPortalIndex portalIndex) {
    }

    record WorldPolicySnapshot(World world, Long time, Boolean storm, Boolean thundering,
            Map<String, String> gameRules) {
        WorldPolicySnapshot {
            gameRules = Map.copyOf(gameRules);
        }
    }

    private record AppliedState(ManagedLobbyConfig config, ManagedLobbyPortalIndex portalIndex,
            ManagedLobbyStore.Snapshot snapshot, ScheduledTask enforcementTask, ScheduledTask scoreboardTask,
            ScheduledTask activationTask) {
    }

    private record NativeActivation(PaperManagedLobbyCoordinator.Activation activation,
            PaperManagedLobbyBridge previous, ManagedLobbyConfig config, List<WorldPolicySnapshot> worldPolicy,
            AtomicBoolean committed) {
    }

    private record PreparedSnapshot(ManagedLobbyStore.Snapshot snapshot, PreparedConfig prepared) {
    }

    private record WriteCandidate(ManagedLobbyStore.Snapshot snapshot, String file, String content,
            ManagedLobbyConfig config, Map<String, Object> response) {
    }

    private record PreparedWrite(ManagedLobbyStore.Snapshot snapshot, String file, String content,
            PreparedConfig prepared, Map<String, Object> response) {
    }

    private record CommittedWrite(PreparedConfig prepared, Map<String, Object> response,
            ManagedLobbyStore.Snapshot snapshot) {
    }

    private record PortalPosition(String world, int x, int y, int z) {
        private Map<String, Object> data() {
            return Map.of("world", world, "x", x, "y", y, "z", z);
        }
    }

    private record PortalSelection(PortalPosition first, PortalPosition second) {
    }

    record PortalOccupancy(String portal, long transition) {
    }

    enum ManagedWorldTransition {
        ENTER,
        LEAVE,
        NONE
    }

    private record CapturedSpawn(ManagedLobbyConfig.Spawn spawn) {
    }

    private record PortalBounds(String world, double minimumX, double minimumY, double minimumZ,
            double maximumX, double maximumY, double maximumZ) {
        private static PortalBounds from(PortalSelection selection) {
            PortalPosition first = selection.first();
            PortalPosition second = selection.second();
            return new PortalBounds(first.world(), Math.min(first.x(), second.x()), Math.min(first.y(), second.y()),
                    Math.min(first.z(), second.z()), Math.max(first.x(), second.x()),
                    Math.max(first.y(), second.y()), Math.max(first.z(), second.z()));
        }
    }

    private record MenuSession(UUID player, UUID token, ManagedLobbyConfig.Menu menu, Inventory inventory) {
    }

    static final class MenuHolder implements InventoryHolder {
        private final UUID owner;
        private final UUID generation;
        private final UUID token;
        private Inventory inventory;

        MenuHolder(UUID owner, UUID generation, UUID token) {
            this.owner = owner;
            this.generation = generation;
            this.token = token;
        }

        @Override
        public @NotNull Inventory getInventory() {
            return Objects.requireNonNull(inventory, "menu inventory is not initialized");
        }
    }

    private static final class SidebarState {
        private final Scoreboard scoreboard;
        private final Objective objective;
        private final List<Team> teams;
        private final List<Component> lines;
        private Component title;

        private SidebarState(Scoreboard scoreboard, Objective objective, List<Team> teams,
                List<Component> lines, Component title) {
            this.scoreboard = scoreboard;
            this.objective = objective;
            this.teams = teams;
            this.lines = lines;
            this.title = title;
        }

        private Scoreboard scoreboard() {
            return scoreboard;
        }

        private Objective objective() {
            return objective;
        }

        private List<Team> teams() {
            return teams;
        }

        private List<Component> lines() {
            return lines;
        }

        private Component title() {
            return title;
        }
    }

    private static final class OverloadedException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private OverloadedException() {
            super("managed lobby pending action limit reached");
        }
    }

    private static final class UnknownResourceException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        private UnknownResourceException(String message) {
            super(message);
        }
    }

    private static final class UnchangedFailedSnapshotException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private UnchangedFailedSnapshotException() {
            super("managed lobby activation snapshot has already failed preflight");
        }
    }

    private static final class BridgeClosedException extends IllegalStateException {
        private static final long serialVersionUID = 1L;

        private BridgeClosedException() {
            super("managed lobby generation is closed");
        }
    }
}
