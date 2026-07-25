package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.RegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.logging.Logger;
import net.kyori.adventure.text.Component;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.block.Block;
import org.bukkit.command.BlockCommandSender;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.UnitTestContainsTooManyAsserts", "PMD.CloseResource"})
class PaperCommandContextBridgeTest {
    @Test
    void commandCallbackContainsOnlyExactDataFields() {
        UUID playerId = UUID.randomUUID();
        Player player = player("Alex", playerId, null);
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(null, null, new AtomicInteger()));

        CompletionStage<?> completion = bridge.executeCommand(player, "sample", "one --flag", Map.of("value", "one"),
                Map.of("flag", true), context -> {
                    assertEquals(java.util.Set.of(
                            "token", "sender", "alias", "input", "arguments", "options"), context.keySet());
                    assertTrue(context.get("token") instanceof String);
                    assertEquals("sample", context.get("alias"));
                    assertEquals("one --flag", context.get("input"));
                    assertEquals(Map.of("value", "one"), context.get("arguments"));
                    assertEquals(Map.of("flag", true), context.get("options"));
                    assertEquals(Map.of("name", "Alex", "kind", "player", "id", playerId.toString()),
                            context.get("sender"));
                    return CompletableFuture.completedFuture(null);
                });
        completion.toCompletableFuture().join();
        assertTrue(completion.toCompletableFuture().isDone());
    }

    @Test
    void richReplyUsesAdventureAndTokenExpiresAfterCallback() {
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("Sender");
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(null, null, new AtomicInteger()));
        AtomicReference<String> token = new AtomicReference<>();

        bridge.executeCommand(sender, "sample", "", Map.of(), Map.of(), context -> {
            token.set((String) context.get("token"));
            assertEquals(Map.of("name", "Sender", "kind", "other"), context.get("sender"));
            return bridge.reply(token.get(), Map.of(
                    "kind", "text", "content", "feedback", "color", "green"));
        }).toCompletableFuture().join();

        verify(sender).sendMessage(any(Component.class));
        assertNotNull(token.get());
        assertFalse(bridge.reply(token.get(), "expired").toCompletableFuture().join());
        assertNull(bridge.findPlayer(token.get(), "Alex").toCompletableFuture().join());
    }

    @Test
    void playerLookupPrefersExactOnlineThenCachedKnownPlayer() {
        Player online = player("Online", UUID.randomUUID(), null);
        OfflinePlayer cached = offlinePlayer("Known", UUID.randomUUID(), false);
        AtomicInteger cachedLookups = new AtomicInteger();
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(online, cached, cachedLookups));
        CommandSender sender = sender("Console");

        bridge.executeCommand(sender, "sample", "", Map.of(), Map.of(), context -> {
            String token = (String) context.get("token");
            assertEquals(Map.of("id", online.getUniqueId().toString(), "name", "Online", "online", true),
                    bridge.findPlayer(token, "Online").toCompletableFuture().join());
            assertEquals(0, cachedLookups.get());
            assertEquals(Map.of("id", cached.getUniqueId().toString(), "name", "Known", "online", false),
                    bridge.findPlayer(token, "Known").toCompletableFuture().join());
            assertNull(bridge.findPlayer(token, "Missing").toCompletableFuture().join());
            return CompletableFuture.completedFuture(null);
        }).toCompletableFuture().join();
        assertEquals(2, cachedLookups.get());
    }

    @Test
    void mainHandInspectionAndExactRemovalRejectMismatches() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenReturn(3);
        AtomicReference<ItemStack> hand = new AtomicReference<>(item);
        PlayerInventory inventory = inventory(hand);
        Player player = player("Alex", UUID.randomUUID(), inventory);
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(null, null, new AtomicInteger()));

        bridge.executeCommand(player, "sample", "", Map.of(), Map.of(), context -> {
            String token = (String) context.get("token");
            assertEquals(Map.of("material", "DIAMOND", "amount", 3),
                    bridge.mainHand(token).toCompletableFuture().join());
            assertFalse(bridge.takeMainHand(token, "EMERALD", 3).toCompletableFuture().join());
            assertFalse(bridge.takeMainHand(token, "DIAMOND", 2).toCompletableFuture().join());
            assertTrue(bridge.takeMainHand(token, "DIAMOND", 3).toCompletableFuture().join());
            assertNull(bridge.mainHand(token).toCompletableFuture().join());
            return CompletableFuture.completedFuture(null);
        }).toCompletableFuture().join();

        assertFalse(bridge.takeMainHand("expired", "DIAMOND", 3).toCompletableFuture().join());
    }

    @Test
    void commandDispatchReturnsWhileCallbackIsIncompleteAndKeepsTokenActive() {
        Server server = mock(Server.class);
        CommandSender sender = sender("Console");
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        AtomicReference<String> token = new AtomicReference<>();

        CompletionStage<?> stage = bridge.executeCommand(sender, "sample", "", Map.of(), Map.of(), context -> {
            token.set((String) context.get("token"));
            return pending;
        });

        assertFalse(stage.toCompletableFuture().isDone());
        assertTrue(bridge.reply(token.get(), "active").toCompletableFuture().join());
        pending.complete(null);
        stage.toCompletableFuture().join();
        assertFalse(bridge.reply(token.get(), "expired").toCompletableFuture().join());
    }

    @Test
    @SuppressWarnings("unchecked")
    void hostOperationsUseCapturedEntityRegionAndGlobalSchedulers() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("scheduler-test"));
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(plugin);
        ScheduledTask scheduledTask = mock(ScheduledTask.class);

        Player player = mock(Player.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        when(player.getName()).thenReturn("Player");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(player.getInventory()).thenReturn(inventory);
        AtomicReference<Consumer<ScheduledTask>> entityOperation = new AtomicReference<>();
        when(entityScheduler.run(eq(plugin), any(), any())).thenAnswer(invocation -> {
            entityOperation.set(invocation.getArgument(1));
            return scheduledTask;
        });
        CompletionStage<?> entityStage = bridge.executeCommand(player, "entity", "", Map.of(), Map.of(), context ->
                bridge.mainHand((String) context.get("token")));
        verify(player, never()).getInventory();
        entityOperation.get().accept(scheduledTask);
        entityStage.toCompletableFuture().join();
        verify(player).getInventory();

        BlockCommandSender blockSender = mock(BlockCommandSender.class);
        Block block = mock(Block.class);
        Location location = mock(Location.class);
        RegionScheduler regionScheduler = mock(RegionScheduler.class);
        when(blockSender.getName()).thenReturn("Block");
        when(blockSender.getBlock()).thenReturn(block);
        when(block.getLocation()).thenReturn(location);
        when(server.getRegionScheduler()).thenReturn(regionScheduler);
        AtomicReference<Consumer<ScheduledTask>> regionOperation = new AtomicReference<>();
        when(regionScheduler.run(eq(plugin), eq(location), any())).thenAnswer(invocation -> {
            regionOperation.set(invocation.getArgument(2));
            return scheduledTask;
        });
        CompletionStage<?> regionStage = bridge.executeCommand(blockSender, "region", "", Map.of(), Map.of(),
                context -> bridge.reply((String) context.get("token"), "region"));
        verify(blockSender, never()).sendMessage(any(Component.class));
        regionOperation.get().accept(scheduledTask);
        regionStage.toCompletableFuture().join();
        verify(blockSender).sendMessage(any(Component.class));

        ConsoleCommandSender console = mock(ConsoleCommandSender.class);
        GlobalRegionScheduler globalScheduler = mock(GlobalRegionScheduler.class);
        when(console.getName()).thenReturn("Console");
        when(server.getGlobalRegionScheduler()).thenReturn(globalScheduler);
        AtomicReference<Consumer<ScheduledTask>> globalOperation = new AtomicReference<>();
        when(globalScheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            globalOperation.set(invocation.getArgument(1));
            return scheduledTask;
        });
        CompletionStage<?> globalStage = bridge.executeCommand(console, "global", "", Map.of(), Map.of(),
                context -> bridge.reply((String) context.get("token"), "global"));
        verify(console, never()).sendMessage(any(Component.class));
        globalOperation.get().accept(scheduledTask);
        globalStage.toCompletableFuture().join();
        verify(console).sendMessage(any(Component.class));
    }

    @Test
    @SuppressWarnings("unchecked")
    void inventoryCleanupUsesViewerEntitySchedulerOutsideOwnedRegion() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Player viewer = mock(Player.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        AtomicReference<Consumer<ScheduledTask>> operation = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("viewer-cleanup-test"));
        when(viewer.getScheduler()).thenReturn(scheduler);
        when(scheduler.run(eq(plugin), any(), any())).thenAnswer(invocation -> {
            operation.set(invocation.getArgument(1));
            return task;
        });
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(
                plugin, () -> false, entity -> false);

        CompletableFuture<Void> close = CompletableFuture.runAsync(
                () -> bridge.closeInventoryViewers(List.of(viewer)));
        while (operation.get() == null) {
            Thread.onSpinWait();
        }
        verify(viewer, never()).closeInventory();
        operation.get().accept(task);
        close.get(1, java.util.concurrent.TimeUnit.SECONDS);
        verify(viewer).closeInventory();
    }

    private static Server server(Player online, OfflinePlayer cached, AtomicInteger cachedLookups) {
        return proxy(Server.class, (target, method, arguments) -> switch (method.getName()) {
            case "getPlayerExact" -> online != null && online.getName().equals(arguments[0]) ? online : null;
            case "getOfflinePlayerIfCached" -> {
                cachedLookups.incrementAndGet();
                yield cached != null && cached.getName().equals(arguments[0]) ? cached : null;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static CommandSender sender(String name) {
        return proxy(CommandSender.class, (target, method, arguments) -> "getName".equals(method.getName())
                ? name : defaultValue(method.getReturnType()));
    }

    private static OfflinePlayer offlinePlayer(String name, UUID id, boolean online) {
        return proxy(OfflinePlayer.class, (target, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> id;
            case "isOnline" -> online;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Player player(String name, UUID id, PlayerInventory inventory) {
        return proxy(Player.class, (target, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> id;
            case "isOnline" -> true;
            case "getInventory" -> inventory;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static PlayerInventory inventory(AtomicReference<ItemStack> hand) {
        return proxy(PlayerInventory.class, (target, method, arguments) -> switch (method.getName()) {
            case "getItemInMainHand" -> hand.get();
            case "setItemInMainHand" -> {
                hand.set((ItemStack) arguments[0]);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
