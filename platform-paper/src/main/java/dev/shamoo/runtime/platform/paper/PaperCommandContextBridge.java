package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.ScriptCallback;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Entity;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;

/** Provides scheduled, data-only access to a command sender while its script callback is active. */
@SuppressWarnings("PMD.PreserveStackTrace")
public final class PaperCommandContextBridge implements AutoCloseable {
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final int MAX_MATERIAL_LENGTH = 128;
    private static final long SCHEDULER_WAIT_SECONDS = 5;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final JavaPlugin plugin;
    private final Server server;
    private final Logger logger;
    private final Predicate<Material> itemMaterial;
    private final BooleanSupplier globalTickThread;
    private final Predicate<Entity> ownedRegion;
    private final Map<String, ActiveSender> activeSenders = new ConcurrentHashMap<>();
    private final PaperRichTextRenderer renderer;

    public PaperCommandContextBridge(JavaPlugin plugin) {
        this(plugin, Bukkit::isGlobalTickThread, Bukkit::isOwnedByCurrentRegion);
    }

    PaperCommandContextBridge(JavaPlugin plugin, BooleanSupplier globalTickThread,
            Predicate<Entity> ownedRegion) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        server = Objects.requireNonNull(plugin.getServer(), "plugin server");
        logger = plugin.getLogger();
        itemMaterial = Material::isItem;
        this.globalTickThread = Objects.requireNonNull(globalTickThread, "globalTickThread");
        this.ownedRegion = Objects.requireNonNull(ownedRegion, "ownedRegion");
        renderer = new PaperRichTextRenderer(plugin,
                (sender, callback, action) -> executeAction(sender, callback, action));
    }

    /** Direct scheduler used by focused unit tests; production always uses the plugin constructor. */
    PaperCommandContextBridge(Server server) {
        plugin = null;
        this.server = Objects.requireNonNull(server, "server");
        logger = Logger.getLogger(PaperCommandContextBridge.class.getName());
        itemMaterial = material -> material != Material.WATER;
        globalTickThread = () -> true;
        ownedRegion = entity -> true;
        renderer = new PaperRichTextRenderer((sender, callback, action) -> executeAction(sender, callback, action));
    }

    public CompletionStage<?> executeCommand(CommandSender sender, String alias, String input,
            Map<String, Object> arguments, Map<String, Object> options,
            Function<Map<String, Object>, CompletionStage<?>> callback) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(options, "options");
        Objects.requireNonNull(callback, "callback");
        Map<String, Object> context = new LinkedHashMap<>();
        context.put("alias", alias);
        context.put("input", input);
        context.put("arguments", Map.copyOf(arguments));
        context.put("options", Map.copyOf(options));
        CompletionStage<?> stage = invoke(sender, context, callback);
        observe(stage, "Script command callback failed");
        return stage;
    }

    public CompletionStage<Boolean> reply(String token, Object descriptor) {
        ActiveSender active = activeSender(token);
        if (active == null) {
            return CompletableFuture.completedFuture(false);
        }
        return schedule(active, () -> {
            active.sender().sendMessage(renderer.render(descriptor, active.sender()));
            return true;
        }, false);
    }

    CompletionStage<?> executeAction(CommandSender sender, ScriptCallback callback, Map<String, Object> action) {
        CompletionStage<?> stage = invoke(sender, action, context -> callback.invoke(List.of(context)));
        observe(stage, "Script action callback failed");
        return stage;
    }

    <T> CompletionStage<T> scheduleActivePlayer(String token, Function<Player, T> operation) {
        Objects.requireNonNull(operation, "operation");
        ActiveSender active = activeSender(token);
        if (active == null || !(active.sender() instanceof Player player)) {
            return CompletableFuture.failedFuture(
                    new SecurityException("operation requires an active player command token"));
        }
        return schedule(active, () -> operation.apply(player), null);
    }

    <T> CompletionStage<T> scheduleGlobal(Supplier<T> operation) {
        Objects.requireNonNull(operation, "operation");
        if (plugin == null) {
            try {
                return CompletableFuture.completedFuture(operation.get());
            } catch (RuntimeException | Error failure) {
                return CompletableFuture.failedFuture(failure);
            }
        }
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            server.getGlobalRegionScheduler().run(plugin, ignored -> {
                try {
                    result.complete(operation.get());
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    void runGlobalAndWait(Runnable operation) {
        Objects.requireNonNull(operation, "operation");
        if (plugin == null || globalTickThread.getAsBoolean()) {
            operation.run();
            return;
        }
        await(scheduleGlobal(() -> {
            operation.run();
            return null;
        }), "global scheduler operation");
    }

    void closeInventoryViewers(Collection<? extends HumanEntity> viewers) {
        List<CompletableFuture<Void>> pending = new ArrayList<>();
        for (HumanEntity viewer : List.copyOf(viewers)) {
            if (plugin == null || ownedRegion.test(viewer)) {
                viewer.closeInventory();
                continue;
            }
            CompletableFuture<Void> completion = new CompletableFuture<>();
            pending.add(completion);
            try {
                ScheduledTask task = viewer.getScheduler().run(plugin, ignored -> {
                    try {
                        viewer.closeInventory();
                        completion.complete(null);
                    } catch (RuntimeException | Error failure) {
                        completion.completeExceptionally(failure);
                    }
                }, () -> completion.complete(null));
                if (task == null) {
                    completion.complete(null);
                }
            } catch (RuntimeException | Error failure) {
                completion.completeExceptionally(failure);
            }
        }
        if (pending.isEmpty()) {
            return;
        }
        try {
            CompletableFuture.allOf(pending.toArray(CompletableFuture[]::new))
                    .get(SCHEDULER_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            logger.log(Level.WARNING, "Interrupted while closing inventory viewers", failure);
        } catch (ExecutionException | TimeoutException failure) {
            logger.log(Level.WARNING, "Unable to close every inventory viewer on its owning region", failure);
        }
    }

    public CompletionStage<Map<String, Object>> findPlayer(String token, String name) {
        ActiveSender active = activeSender(token);
        if (active == null || !bounded(name, MAX_PLAYER_NAME_LENGTH, false)) {
            return CompletableFuture.completedFuture(null);
        }
        return schedule(active, () -> {
            Player online = server.getPlayerExact(name);
            if (online != null) {
                return playerData(online);
            }
            OfflinePlayer cached = server.getOfflinePlayerIfCached(name);
            return cached == null || cached.getName() == null ? null : playerData(cached);
        }, null);
    }

    public CompletionStage<Map<String, Object>> mainHand(String token) {
        ActiveSender active = activeSender(token);
        if (active == null || !(active.sender() instanceof Player player)) {
            return CompletableFuture.completedFuture(null);
        }
        return schedule(active, () -> {
            PlayerInventory inventory = player.getInventory();
            ItemStack item = inventory.getItemInMainHand();
            return item == null || isAir(item.getType()) ? null : itemData(item);
        }, null);
    }

    public CompletionStage<Boolean> takeMainHand(String token, String expectedMaterial, int expectedAmount) {
        ActiveSender active = activeSender(token);
        if (active == null || !(active.sender() instanceof Player player)
                || expectedAmount <= 0) {
            return CompletableFuture.completedFuture(false);
        }
        return schedule(active, () -> {
            Material material = material(expectedMaterial);
            PlayerInventory inventory = player.getInventory();
            ItemStack item = material == null ? null : inventory.getItemInMainHand();
            if (item == null || item.getType() != material || item.getAmount() != expectedAmount) {
                return false;
            }
            inventory.setItemInMainHand(null);
            return true;
        }, false);
    }

    private CompletionStage<?> invoke(CommandSender sender, Map<String, Object> values,
            Function<Map<String, Object>, CompletionStage<?>> callback) {
        ActiveSender active = activate(sender);
        try {
            Map<String, Object> context = new LinkedHashMap<>();
            context.put("token", active.token());
            context.put("sender", active.senderData());
            context.putAll(values);
            CompletionStage<?> completion = Objects.requireNonNull(
                    callback.apply(java.util.Collections.unmodifiableMap(context)), "callback completion");
            completion.whenComplete((ignored, failure) -> activeSenders.remove(active.token(), active));
            return completion;
        } catch (RuntimeException | Error failure) {
            activeSenders.remove(active.token(), active);
            return CompletableFuture.failedFuture(failure);
        }
    }

    private ActiveSender activate(CommandSender sender) {
        Map<String, Object> data = senderData(sender);
        DispatchRoute route = route(sender);
        byte[] bytes = new byte[TOKEN_BYTES];
        ActiveSender active;
        do {
            RANDOM.nextBytes(bytes);
            String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            active = new ActiveSender(token, sender, data, route);
        } while (activeSenders.putIfAbsent(active.token(), active) != null);
        return active;
    }

    private DispatchRoute route(CommandSender sender) {
        if (plugin == null) {
            return (operation, retired) -> operation.run();
        }
        if (sender instanceof Entity entity) {
            EntityScheduler scheduler = entity.getScheduler();
            return (operation, retired) -> {
                ScheduledTask task = scheduler.run(plugin, ignored -> operation.run(), retired);
                if (task == null) {
                    retired.run();
                }
            };
        }
        if (sender instanceof BlockCommandSender blockSender) {
            Block block = blockSender.getBlock();
            Location location = block.getLocation();
            RegionScheduler scheduler = server.getRegionScheduler();
            return (operation, retired) -> scheduler.run(plugin, location, ignored -> operation.run());
        }
        GlobalRegionScheduler scheduler = server.getGlobalRegionScheduler();
        return (operation, retired) -> scheduler.run(plugin, ignored -> operation.run());
    }

    private <T> CompletionStage<T> schedule(ActiveSender active, Supplier<T> operation, T retiredValue) {
        CompletableFuture<T> result = new CompletableFuture<>();
        try {
            active.route().execute(() -> {
                try {
                    result.complete(operation.get());
                } catch (RuntimeException | Error failure) {
                    result.completeExceptionally(failure);
                }
            }, () -> result.complete(retiredValue));
        } catch (RuntimeException | Error failure) {
            result.completeExceptionally(failure);
        }
        return result;
    }

    private static <T> T await(CompletionStage<T> stage, String operation) {
        try {
            return stage.toCompletableFuture().get(SCHEDULER_WAIT_SECONDS, TimeUnit.SECONDS);
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while awaiting " + operation, failure);
        } catch (ExecutionException failure) {
            Throwable cause = unwrap(failure);
            if (cause instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new IllegalStateException("unable to complete " + operation, cause);
        } catch (TimeoutException failure) {
            throw new IllegalStateException("timed out while awaiting " + operation, failure);
        }
    }

    private ActiveSender activeSender(String token) {
        return bounded(token, MAX_TOKEN_LENGTH, false) ? activeSenders.get(token) : null;
    }

    static Map<String, Object> senderData(CommandSender sender) {
        if (sender instanceof Player player) {
            return Map.of("name", player.getName(), "kind", "player", "id", player.getUniqueId().toString());
        }
        return Map.of("name", sender.getName(), "kind",
                sender instanceof ConsoleCommandSender ? "console" : "other");
    }

    static Map<String, Object> playerData(OfflinePlayer player) {
        return Map.of("id", player.getUniqueId().toString(), "name", player.getName(), "online", player.isOnline());
    }

    private static Map<String, Object> itemData(ItemStack item) {
        return Map.of("material", item.getType().name(), "amount", item.getAmount());
    }

    private Material material(String name) {
        if (!bounded(name, MAX_MATERIAL_LENGTH, false)) {
            return null;
        }
        Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
        return material == null || !itemMaterial.test(material) || isAir(material) ? null : material;
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private void observe(CompletionStage<?> stage, String message) {
        stage.whenComplete((ignored, failure) -> {
            if (failure != null) {
                if (logger.isLoggable(Level.WARNING)) {
                    logger.log(Level.WARNING, message, unwrap(failure));
                }
            }
        });
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof CompletionException || current instanceof ExecutionException)
                && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    private static boolean bounded(String value, int maximumLength, boolean allowEmpty) {
        return value != null && value.length() <= maximumLength && (allowEmpty || !value.isBlank());
    }

    @Override
    public void close() {
        activeSenders.clear();
    }

    private record ActiveSender(String token, CommandSender sender, Map<String, Object> senderData,
            DispatchRoute route) {
        private ActiveSender {
            senderData = Map.copyOf(senderData);
        }
    }

    @FunctionalInterface
    private interface DispatchRoute {
        void execute(Runnable operation, Runnable retired);
    }
}
