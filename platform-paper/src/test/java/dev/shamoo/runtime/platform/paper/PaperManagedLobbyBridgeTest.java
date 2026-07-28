package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import dev.shamoo.runtime.core.InvocationController;
import dev.shamoo.runtime.core.InvocationSnapshot;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import org.bukkit.Bukkit;
import org.bukkit.GameRule;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.entity.EntityExhaustionEvent;
import org.bukkit.event.entity.FoodLevelChangeEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.weather.WeatherChangeEvent;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.mockito.MockedStatic;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.CloseResource", "PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.AvoidDuplicateLiterals"})
class PaperManagedLobbyBridgeTest {
    @TempDir
    Path temporary;

    @Test
    void cancelsPlayerDamageFoodAndExhaustionNatively() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getName()).thenReturn("ShamooRuntime");
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(true, 0, 0, 0, 0));
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary), coordinator, invocations, 4);
        setConfig(bridge, ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));
        when(coordinator.isActive(bridge)).thenReturn(true);
        Player player = mock(Player.class);
        World world = mock(World.class);
        when(world.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(world);

        EntityDamageEvent damage = mock(EntityDamageEvent.class);
        when(damage.getEntityType()).thenReturn(EntityType.PLAYER);
        when(damage.getEntity()).thenReturn(player);
        bridge.onDamage(damage);

        FoodLevelChangeEvent food = mock(FoodLevelChangeEvent.class);
        when(food.getEntity()).thenReturn(player);
        bridge.onFood(food);

        EntityExhaustionEvent exhaustion = mock(EntityExhaustionEvent.class);
        when(exhaustion.getEntity()).thenReturn(player);
        bridge.onExhaustion(exhaustion);

        verify(damage).setCancelled(true);
        verify(food).setCancelled(true);
        verify(exhaustion).setCancelled(true);
        verify(player).setFoodLevel(20);
        verify(player).setSaturation(5.0F);
        verify(player, times(2)).setExhaustion(0.0F);
        when(coordinator.isActive(bridge)).thenReturn(false);
        bridge.close();
    }

    @Test
    void leavesUnmanagedDamageUntouchedAndProtectsActiveGenerationDuringDrain() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(true, 0, 0, 0, 0));
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary), coordinator, invocations, 4);
        setConfig(bridge, ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));
        when(coordinator.isActive(bridge)).thenReturn(true);
        Player player = mock(Player.class);
        World other = mock(World.class);
        when(other.getName()).thenReturn("survival");
        when(player.getWorld()).thenReturn(other);
        EntityDamageEvent unmanaged = mock(EntityDamageEvent.class);
        when(unmanaged.getEntityType()).thenReturn(EntityType.PLAYER);
        when(unmanaged.getEntity()).thenReturn(player);

        bridge.onDamage(unmanaged);
        verify(unmanaged, never()).setCancelled(true);

        World managed = mock(World.class);
        when(managed.getName()).thenReturn("world");
        when(player.getWorld()).thenReturn(managed);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(false, 0, 0, 0, 0));
        EntityDamageEvent staged = mock(EntityDamageEvent.class);
        when(staged.getEntityType()).thenReturn(EntityType.PLAYER);
        when(staged.getEntity()).thenReturn(player);
        bridge.onDamage(staged);
        verify(staged).setCancelled(true);

        EntityDamageByEntityEvent attack = mock(EntityDamageByEntityEvent.class);
        when(attack.getEntityType()).thenReturn(EntityType.ZOMBIE);
        bridge.onDamage(attack);
        verify(attack, never()).setCancelled(true);

        when(coordinator.isActive(bridge)).thenReturn(false);
        bridge.close();
    }

    @Test
    void settlesQueuedHostWorkWhenClosed() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("closing"));
        store.ensure();
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                store, coordinator, invocations, 4);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            synchronized (store) {
                locked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try {
            assertTrue(locked.await(1, TimeUnit.SECONDS));
            CompletionStage<Map<String, Object>> stage = bridge.invoke(List.of(Map.of("operation", "read")));
            bridge.close();

            Map<String, Object> result = stage.toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertFalse((Boolean) result.get("ok"));
            assertEquals("unavailable", result.get("state"));
        } finally {
            release.countDown();
            holder.get(1, TimeUnit.SECONDS);
            assertTrue(((ThreadPoolExecutor) field(bridge, "fileExecutor")).awaitTermination(1, TimeUnit.SECONDS));
        }
    }

    @Test
    void validatesNativeItemsBeforePersistingAWrite() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("validation"));
        store.ensure();
        String previous = store.read("items.yml");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(), store,
                mock(PaperManagedLobbyCoordinator.class), mock(InvocationController.class), 4);
        String invalidItems = "items:\n  - id: invalid\n    slot: 0\n"
                + "    material: NOT_A_REAL_MATERIAL\n    action: {type: none}\n";

        Map<String, Object> result = bridge.invoke(List.of(Map.of(
                "operation", "write", "file", "items.yml", "content", invalidItems,
                "reload", true))).toCompletableFuture().get(1, TimeUnit.SECONDS);

        assertFalse((Boolean) result.get("ok"));
        assertEquals("invalid", result.get("state"));
        assertEquals(previous, store.read("items.yml"));
        bridge.close();
    }

    @Test
    void rejectsNativeExecutionUntilCandidateOwnsTheCoordinator() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("standby")), coordinator, mock(InvocationController.class), 4);
        setConfig(bridge, ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));

        Map<String, Object> result = bridge.invoke(List.of(Map.of(
                "operation", "execute", "action", "portal-list"))).toCompletableFuture().join();

        assertFalse((Boolean) result.get("ok"));
        assertEquals("unavailable", result.get("state"));
        bridge.close();
    }

    @Test
    void returnsCompletionStagesForImmediateStatusAndInvalidOutcomes() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        InvocationController invocations = mock(InvocationController.class);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(false, 0, 0, 0, 0));
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("immediate")), mock(PaperManagedLobbyCoordinator.class),
                invocations, 4);

        CompletionStage<Map<String, Object>> status = bridge.invoke(List.of(Map.of("operation", "status")));
        CompletionStage<Map<String, Object>> invalid = bridge.invoke(List.of());

        assertTrue(status.toCompletableFuture().isDone());
        assertEquals("uninitialized", status.toCompletableFuture().join().get("state"));
        assertTrue(invalid.toCompletableFuture().isDone());
        assertEquals("invalid", invalid.toCompletableFuture().join().get("state"));
        bridge.close();
    }

    @Test
    void refreshesAStaleStandbyBeforeCoordinatorHandoff() throws Exception {
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("stale-standby"));
        store.ensure();
        for (Map.Entry<String, String> entry : ManagedLobbyConfigTest.files().entrySet()) {
            store.write(entry.getKey(), entry.getValue());
        }
        ManagedLobbyStore.Snapshot prepared = store.snapshot();
        ManagedLobbyConfig staleConfig = ManagedLobbyConfig.parse(prepared.files());
        Files.writeString(store.directory().resolve("messages.yml"),
                "messages: {latest: '<green>Latest</green>'}\ntitles: []\nsounds: []\nparticles: []\n");

        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        PaperManagedLobbyBridge previous = mock(PaperManagedLobbyBridge.class);
        InvocationController invocations = mock(InvocationController.class);
        World world = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(scheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(task);
        BlockingQueue<Runnable> globalOperations = new LinkedBlockingQueue<>();
        doAnswer(invocation -> {
            globalOperations.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(true, 0, 0, 0, 0));
        when(world.getName()).thenReturn("world");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(), store,
                coordinator, invocations, 8);
        setConfig(bridge, staleConfig);
        setField(bridge, "preparedSnapshot", prepared);
        ((AtomicBoolean) field(bridge, "registered")).set(true);
        AtomicReference<ManagedLobbyConfig> activated = new AtomicReference<>();
        when(coordinator.activate(bridge)).thenReturn(
                new PaperManagedLobbyCoordinator.Activation(previous, false, true));
        doAnswer(invocation -> {
            activated.set((ManagedLobbyConfig) field(bridge, "config"));
            return null;
        }).when(coordinator).commit(eq(bridge), any());

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());

            bridge.activateWhenAdmitted();
            verify(coordinator, never()).activate(bridge);
            globalOperations.poll(1, TimeUnit.SECONDS).run();
            globalOperations.poll(1, TimeUnit.SECONDS).run();

            verify(coordinator, timeout(1_000).times(1)).activate(bridge);
            assertTrue(activated.get().messages().containsKey("latest"));
            bridge.close();
        }
    }

    @Test
    void standbySnapshotVerificationIsAsyncAndCoalescesPolls() throws Exception {
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("async-standby"));
        store.ensure();
        for (Map.Entry<String, String> entry : ManagedLobbyConfigTest.files().entrySet()) {
            store.write(entry.getKey(), entry.getValue());
        }
        ManagedLobbyStore.Snapshot prepared = store.snapshot();
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        World world = mock(World.class);
        BlockingQueue<Runnable> globalOperations = new LinkedBlockingQueue<>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        doAnswer(invocation -> {
            globalOperations.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(true, 0, 0, 0, 0));
        when(world.getName()).thenReturn("world");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(), store,
                coordinator, invocations, 8);
        setConfig(bridge, ManagedLobbyConfig.parse(prepared.files()));
        setField(bridge, "preparedSnapshot", prepared);
        when(coordinator.activate(bridge)).thenReturn(new PaperManagedLobbyCoordinator.Activation(null, true, true));
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CompletableFuture<Void> holder = CompletableFuture.runAsync(() -> {
            synchronized (store) {
                locked.countDown();
                try {
                    release.await(2, TimeUnit.SECONDS);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        });

        try (bridge; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            try {
                assertTrue(locked.await(1, TimeUnit.SECONDS));

                CompletableFuture.runAsync(bridge::activateWhenAdmitted).get(1, TimeUnit.SECONDS);
                bridge.activateWhenAdmitted();
                verify(coordinator, never()).activate(bridge);
                release.countDown();
                Runnable activation = globalOperations.poll(1, TimeUnit.SECONDS);
                assertNotNull(activation);
                activation.run();

                verify(coordinator, times(1)).activate(bridge);
                verify(coordinator, times(1)).commit(eq(bridge), any());
            } finally {
                release.countDown();
                holder.get(1, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void coordinatorFailureDoesNotApplyCandidateWorldPolicy() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        World world = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(true, 0, 0, 0, 0));
        doThrow(new IllegalStateException("channel registration failed")).when(coordinator).activate(any());
        Map<String, String> files = ManagedLobbyConfigTest.files();
        files.put("config.yml", "worlds:\n  - name: world\n    time: 1200\n");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("activation-failure")), coordinator, invocations, 4);
        setConfig(bridge, ManagedLobbyConfig.parse(files));

        try (bridge; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            assertThrows(IllegalStateException.class, bridge::activateNative);
            verify(world, never()).setTime(anyLong());
            verify(coordinator, never()).commit(eq(bridge), any());
        }
    }

    @Test
    void failedLiveSetupRollsBackAndReappliesPreviousPolicy() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        PaperManagedLobbyBridge previous = mock(PaperManagedLobbyBridge.class);
        InvocationController invocations = mock(InvocationController.class);
        World world = mock(World.class);
        Player player = mock(Player.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(true, 0, 0, 0, 0));
        when(coordinator.activate(any())).thenReturn(
                new PaperManagedLobbyCoordinator.Activation(previous, false, true));
        when(world.getName()).thenReturn("world");
        when(world.getTime()).thenReturn(6000L);
        when(world.hasStorm()).thenReturn(false);
        when(world.isThundering()).thenReturn(false);
        when(world.getGameRuleValue(GameRule.KEEP_INVENTORY)).thenReturn(false);
        when(player.getScheduler()).thenReturn(entityScheduler);
        doThrow(new IllegalStateException("entity scheduling failed")).when(entityScheduler)
                .execute(eq(plugin), any(Runnable.class), isNull(), eq(1L));
        Map<String, String> files = ManagedLobbyConfigTest.files();
        files.put("config.yml", "worlds:\n  - name: world\n    time: 1200\n    storm: true\n"
                + "    thundering: true\n    game-rules: {keepInventory: true}\n");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("live-setup-failure")), coordinator, invocations, 4);
        setConfig(bridge, ManagedLobbyConfig.parse(files));

        try (bridge; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            assertThrows(IllegalStateException.class, bridge::activateNative);

            verify(world).setTime(1200L);
            verify(world).setTime(6000L);
            verify(world).setStorm(true);
            verify(world).setStorm(false);
            verify(world).setThundering(true);
            verify(world).setThundering(false);
            verify(world).setGameRule(GameRule.KEEP_INVENTORY, true);
            verify(world).setGameRule(GameRule.KEEP_INVENTORY, false);
            verify(coordinator).rollback(eq(bridge), any());
            verify(previous).reactivateNative();
            verify(coordinator, never()).commit(eq(bridge), any());
        }
    }

    @Test
    void failedActiveReloadRestoresPublishedStateAndKeepsOldTasks() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        ScheduledTask oldEnforcement = mock(ScheduledTask.class);
        ScheduledTask oldScoreboard = mock(ScheduledTask.class);
        ScheduledTask oldActivation = mock(ScheduledTask.class);
        ScheduledTask candidateEnforcement = mock(ScheduledTask.class);
        ScheduledTask candidateScoreboard = mock(ScheduledTask.class);
        World world = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(scheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong()))
                .thenReturn(candidateEnforcement, candidateScoreboard);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(true, 0, 0, 0, 0));
        when(world.getName()).thenReturn("world");
        when(world.getTime()).thenReturn(6000L);
        when(world.hasStorm()).thenReturn(false);
        when(world.isThundering()).thenReturn(false);
        when(world.getGameRuleValue(GameRule.KEEP_INVENTORY)).thenReturn(false);
        Map<String, String> oldFiles = ManagedLobbyConfigTest.files();
        ManagedLobbyConfig oldConfig = ManagedLobbyConfig.parse(oldFiles);
        ManagedLobbyPortalIndex oldPortalIndex = new ManagedLobbyPortalIndex(oldConfig.portals());
        ManagedLobbyStore.Snapshot oldSnapshot = new ManagedLobbyStore.Snapshot(7, oldFiles);
        Map<String, String> candidateFiles = ManagedLobbyConfigTest.files();
        candidateFiles.put("config.yml", "worlds:\n  - name: world\n    time: 1200\n    storm: true\n"
                + "    thundering: true\n    game-rules: {keepInventory: true}\n");
        ManagedLobbyConfig candidate = ManagedLobbyConfig.parse(candidateFiles);
        ManagedLobbyStore.Snapshot candidateSnapshot = new ManagedLobbyStore.Snapshot(8, candidateFiles);
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("active-reload-failure")), coordinator, invocations, 8);
        setConfig(bridge, oldConfig);
        setField(bridge, "portalIndex", oldPortalIndex);
        setField(bridge, "preparedSnapshot", oldSnapshot);
        setField(bridge, "enforcementTask", oldEnforcement);
        setField(bridge, "scoreboardTask", oldScoreboard);
        setField(bridge, "activationTask", oldActivation);
        ((AtomicBoolean) field(bridge, "registered")).set(true);
        UUID viewer = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        Map<UUID, ManagedLobbyConfig.VisibilityMode> visibility =
                (Map<UUID, ManagedLobbyConfig.VisibilityMode>) field(bridge, "visibility");
        visibility.put(viewer, ManagedLobbyConfig.VisibilityMode.STAFF);
        @SuppressWarnings("unchecked")
        Map<UUID, Object> menuSessions = (Map<UUID, Object>) field(bridge, "menuSessions");
        Object menuSession = menuSession(viewer);
        menuSessions.put(viewer, menuSession);
        when(coordinator.isActive(bridge)).thenReturn(true);
        when(coordinator.activate(bridge)).thenReturn(
                new PaperManagedLobbyCoordinator.Activation(bridge, false, false));
        doThrow(new IllegalStateException("injected commit failure")).when(coordinator).commit(eq(bridge), any());

        try (bridge; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            PaperManagedLobbyBridge.PreparedConfig prepared = new PaperManagedLobbyBridge.PreparedConfig(
                    candidate, new ManagedLobbyPortalIndex(candidate.portals()));

            assertThrows(IllegalStateException.class,
                    () -> bridge.applyPreparedLocked(prepared, candidateSnapshot, true));

            assertSame(oldConfig, field(bridge, "config"));
            assertSame(oldPortalIndex, field(bridge, "portalIndex"));
            assertSame(oldSnapshot, field(bridge, "preparedSnapshot"));
            assertSame(oldEnforcement, field(bridge, "enforcementTask"));
            assertSame(oldScoreboard, field(bridge, "scoreboardTask"));
            assertSame(oldActivation, field(bridge, "activationTask"));
            assertEquals(ManagedLobbyConfig.VisibilityMode.STAFF, visibility.get(viewer));
            assertSame(menuSession, menuSessions.get(viewer));
            assertTrue(((AtomicBoolean) field(bridge, "registered")).get());
            verify(candidateEnforcement).cancel();
            verify(candidateScoreboard).cancel();
            verify(oldEnforcement, never()).cancel();
            verify(oldScoreboard, never()).cancel();
            verify(oldActivation, never()).cancel();
            verify(world).setTime(1200L);
            verify(world).setTime(6000L);
            verify(world).setStorm(true);
            verify(world).setStorm(false);
            verify(world).setThundering(true);
            verify(world).setThundering(false);
            verify(world).setGameRule(GameRule.KEEP_INVENTORY, true);
            verify(world).setGameRule(GameRule.KEEP_INVENTORY, false);
            verify(coordinator).rollback(eq(bridge), any());
        }
    }

    @Test
    void restoresCandidateOnlyWorldSnapshotWithoutPreviousOwner() {
        World world = mock(World.class);
        RuntimeException failure = new RuntimeException("injected activation failure");
        PaperManagedLobbyBridge.WorldPolicySnapshot snapshot = new PaperManagedLobbyBridge.WorldPolicySnapshot(
                world, 6000L, false, false, Map.of("keepInventory", "false"));

        PaperManagedLobbyBridge.restoreWorldPolicy(List.of(snapshot), failure);

        verify(world).setTime(6000L);
        verify(world).setStorm(false);
        verify(world).setThundering(false);
        verify(world).setGameRule(GameRule.KEEP_INVENTORY, false);
        assertEquals(0, failure.getSuppressed().length);
    }

    @Test
    void backsOffFailedRefreshUntilSnapshotChanges() {
        Map<String, String> files = ManagedLobbyConfigTest.files();
        ManagedLobbyStore.Snapshot failed = new ManagedLobbyStore.Snapshot(3, files);
        ManagedLobbyStore.Snapshot unchanged = new ManagedLobbyStore.Snapshot(3, files);
        Map<String, String> changedFiles = new java.util.LinkedHashMap<>(files);
        changedFiles.put("messages.yml", "messages: {changed: true}\ntitles: []\nsounds: []\nparticles: []\n");
        ManagedLobbyStore.Snapshot externallyChanged = new ManagedLobbyStore.Snapshot(3, changedFiles);

        assertFalse(PaperManagedLobbyBridge.refreshBackoffElapsed(99, 100));
        assertTrue(PaperManagedLobbyBridge.refreshBackoffElapsed(100, 100));
        assertFalse(PaperManagedLobbyBridge.shouldRetryFailedSnapshot(failed, unchanged));
        assertTrue(PaperManagedLobbyBridge.shouldRetryFailedSnapshot(failed, externallyChanged));
    }

    @Test
    void reloadReturnsMessagesFromTheActivatedSnapshot() throws Exception {
        ManagedLobbyStore actualStore = new ManagedLobbyStore(temporary.resolve("reload-messages"));
        actualStore.ensure();
        Map<String, String> files = ManagedLobbyConfigTest.files();
        String messages = "messages: {accepted: '<green>Accepted</green>'}\ntitles: []\nsounds: []\nparticles: []\n";
        files.put("messages.yml", messages);
        files.put("servers.yml", "servers:\n  - id: enabled\n    enabled: true\n    target: Enabled\n"
                + "    display-name: Enabled\n  - id: disabled\n    enabled: false\n    target: Disabled\n"
                + "    display-name: Disabled\n");
        for (Map.Entry<String, String> entry : files.entrySet()) {
            actualStore.write(entry.getKey(), entry.getValue());
        }
        ManagedLobbyStore store = spy(actualStore);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        World world = mock(World.class);
        BlockingQueue<Runnable> globalOperations = new LinkedBlockingQueue<>();
        AtomicBoolean inGlobalCallback = new AtomicBoolean();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(scheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(task);
        doAnswer(invocation -> {
            Runnable operation = invocation.getArgument(1);
            globalOperations.add(() -> {
                assertTrue(inGlobalCallback.compareAndSet(false, true));
                try {
                    operation.run();
                } finally {
                    inGlobalCallback.set(false);
                }
            });
            return null;
        }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        doAnswer(invocation -> {
            assertFalse(inGlobalCallback.get());
            return invocation.callRealMethod();
        }).when(store).requireUnchanged(any(ManagedLobbyStore.Snapshot.class));
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(false, 0, 0, 0, 0));
        when(world.getName()).thenReturn("world");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(), store,
                coordinator, invocations, 8);
        ((AtomicBoolean) field(bridge, "registered")).set(true);

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            CompletionStage<Map<String, Object>> reload = bridge.invoke(List.of(Map.of("operation", "reload")));
            globalOperations.poll(1, TimeUnit.SECONDS).run();
            globalOperations.poll(1, TimeUnit.SECONDS).run();

            Map<String, Object> result = reload.toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertTrue((Boolean) result.get("ok"));
            assertEquals(messages, result.get("messagesContent"));
            assertEquals(2, result.get("servers"));
            assertEquals(2, bridge.invoke(List.of(Map.of("operation", "status")))
                    .toCompletableFuture().join().get("servers"));
            assertNotNull(field(bridge, "activationTask"));
            verify(coordinator, never()).activate(bridge);
            verify(store, atLeastOnce()).requireUnchanged(any(ManagedLobbyStore.Snapshot.class));
            verify(store, atLeastOnce()).runAtVersion(anyLong(), any(Runnable.class));
            verify(store, never()).runAtSnapshot(any(ManagedLobbyStore.Snapshot.class), any(Runnable.class));
            bridge.close();
        }
    }

    @Test
    void admittedActiveWriteReloadsAfterAdmissionCloses() throws Exception {
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("admitted-active-write"));
        store.ensure();
        Map<String, String> files = ManagedLobbyConfigTest.files();
        for (Map.Entry<String, String> entry : files.entrySet()) {
            store.write(entry.getKey(), entry.getValue());
        }
        ManagedLobbyConfig oldConfig = ManagedLobbyConfig.parse(files);
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        World world = mock(World.class);
        BlockingQueue<Runnable> globalOperations = new LinkedBlockingQueue<>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(scheduler.runAtFixedRate(eq(plugin), any(), anyLong(), anyLong())).thenReturn(task);
        doAnswer(invocation -> {
            globalOperations.add(invocation.getArgument(1));
            return null;
        }).when(scheduler).execute(eq(plugin), any(Runnable.class));
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(false, 1, 0, 0, 0));
        when(world.getName()).thenReturn("world");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(), store,
                coordinator, invocations, 8);
        setConfig(bridge, oldConfig);
        setField(bridge, "preparedSnapshot", store.snapshot());
        ((AtomicBoolean) field(bridge, "registered")).set(true);
        when(coordinator.isActive(bridge)).thenReturn(true);
        when(coordinator.activate(bridge)).thenReturn(
                new PaperManagedLobbyCoordinator.Activation(bridge, false, false));
        String messages = "messages: {admitted: '<green>Admitted</green>'}\ntitles: []\nsounds: []\nparticles: []\n";

        try (bridge; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(world);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            CompletionStage<Map<String, Object>> write = bridge.invoke(List.of(Map.of(
                    "operation", "write", "file", "messages.yml", "content", messages)), true);
            globalOperations.poll(1, TimeUnit.SECONDS).run();
            globalOperations.poll(1, TimeUnit.SECONDS).run();

            Map<String, Object> result = write.toCompletableFuture().get(1, TimeUnit.SECONDS);
            assertTrue((Boolean) result.get("ok"));
            assertEquals(messages, store.read("messages.yml"));
            assertTrue(((ManagedLobbyConfig) field(bridge, "config")).messages().containsKey("admitted"));
            verify(coordinator).activate(bridge);
            verify(coordinator).commit(eq(bridge), any());
            when(coordinator.isActive(bridge)).thenReturn(false);
        }
    }

    @Test
    void nonAdmittedActiveMutationFailsBeforePersistence() throws Exception {
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("non-admitted-active-write"));
        store.ensure();
        String previous = store.read("messages.yml");
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(coordinator.ownsActive(any())).thenReturn(true);
        when(invocations.snapshot()).thenReturn(new InvocationSnapshot(false, 0, 0, 0, 0));
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(), store,
                coordinator, invocations, 4);
        setConfig(bridge, ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));

        CompletionStage<Map<String, Object>> write = bridge.invoke(List.of(Map.of(
                "operation", "write", "file", "messages.yml",
                "content", "messages: {rejected: true}\n")), false);
        Map<String, Object> result = write.toCompletableFuture().join();

        assertFalse((Boolean) result.get("ok"));
        assertEquals("unavailable", result.get("state"));
        assertTrue(result.get("error").toString().contains("admitted"));
        assertEquals(previous, store.read("messages.yml"));
        assertEquals(0, ((AtomicInteger) field(bridge, "pendingActions")).get());
        verify(scheduler, never()).execute(eq(plugin), any(Runnable.class));
        when(coordinator.ownsActive(any())).thenReturn(false);
        bridge.close();
    }

    @Test
    void setSpawnDistinguishesOutsideManagedPlayerFromOfflinePlayer() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        InvocationController invocations = mock(InvocationController.class);
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("setspawn")), coordinator, invocations, 4);
        setConfig(bridge, ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        World outside = mock(World.class);
        EntityScheduler entity = mock(EntityScheduler.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(coordinator.isActive(bridge)).thenReturn(true);
        when(outside.getName()).thenReturn("survival");
        when(player.getWorld()).thenReturn(outside);
        when(player.isOnline()).thenReturn(true);
        when(player.getScheduler()).thenReturn(entity);
        doAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return null;
        }).when(global).execute(eq(plugin), any(Runnable.class));
        when(entity.execute(eq(plugin), any(Runnable.class), any(Runnable.class), eq(1L))).thenAnswer(invocation -> {
            invocation.<Runnable>getArgument(1).run();
            return true;
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getPlayer(playerId)).thenReturn(player);
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            Map<String, Object> result = bridge.invoke(List.of(Map.of(
                    "operation", "execute", "action", "setspawn", "player", playerId.toString())), true)
                    .toCompletableFuture().get(1, TimeUnit.SECONDS);

            assertFalse((Boolean) result.get("ok"));
            assertEquals("unavailable", result.get("state"));
            assertTrue(result.get("error").toString().contains("outside managed"));
        }
        when(coordinator.isActive(bridge)).thenReturn(false);
        bridge.close();
    }

    @Test
    void portalDestinationPreservesCurrentSnapshotFields() {
        Map<String, String> files = ManagedLobbyConfigTest.files();
        files.put("menus.yml", "menus:\n  - id: game-selector\n    rows: 1\n    title: Games\n    slots: []\n");
        files.put("servers.yml", "servers:\n  - id: survival\n    enabled: true\n    target: Survival\n"
                + "    display-name: Survival\n");
        files.put("portals.yml", "portals:\n  - id: current\n    enabled: false\n    world: world\n"
                + "    min: {x: 41, y: 70, z: 43}\n    max: {x: 45, y: 74, z: 47}\n"
                + "    permission: current.permission\n    priority: 77\n    cooldown-ms: 4321\n"
                + "    destination: survival\n    visualize: true\n");
        ManagedLobbyConfig current = ManagedLobbyConfig.parse(files);
        ManagedLobbyPortalIndex.Portal source = current.portals().getFirst();

        ManagedLobbyPortalIndex.Portal menu = PaperManagedLobbyBridge.portalDestination(
                current, source, "menu", "game-selector");
        ManagedLobbyPortalIndex.Portal spawn = PaperManagedLobbyBridge.portalDestination(
                current, source, "spawn", null);

        assertEquals(41, menu.minimumX());
        assertEquals("current.permission", menu.permission());
        assertEquals(77, menu.priority());
        assertEquals(4321, menu.cooldownMillis());
        assertTrue(menu.visualize());
        assertEquals(ManagedLobbyConfig.ActionType.MENU, menu.action().type());
        assertEquals("game-selector", menu.action().target());
        assertNull(menu.destination());
        assertEquals(ManagedLobbyConfig.ActionType.SPAWN, spawn.action().type());
        assertNull(spawn.destination());
    }

    @Test
    void removesPreviousScoreboardStateOnQuit() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("quit")), mock(PaperManagedLobbyCoordinator.class),
                mock(InvocationController.class), 4);
        UUID playerId = UUID.randomUUID();
        @SuppressWarnings("unchecked")
        Map<UUID, Scoreboard> scoreboards = (Map<UUID, Scoreboard>) field(bridge, "previousScoreboards");
        scoreboards.put(playerId, mock(Scoreboard.class));
        Player player = mock(Player.class);
        PlayerQuitEvent event = mock(PlayerQuitEvent.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(event.getPlayer()).thenReturn(player);

        bridge.onQuit(event);

        assertFalse(scoreboards.containsKey(playerId));
        bridge.close();
    }

    @Test
    void schedulesVisibilityRefreshWhenPlayerEntersManagedWorld() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("world-entry")), coordinator,
                mock(InvocationController.class), 4);
        Map<String, String> files = ManagedLobbyConfigTest.files();
        files.put("config.yml", "join: {reset: false}\nworlds:\n  - name: world\n");
        setConfig(bridge, ManagedLobbyConfig.parse(files));
        when(coordinator.isActive(bridge)).thenReturn(true);
        Player player = mock(Player.class);
        PlayerInventory inventory = mock(PlayerInventory.class);
        EntityScheduler scheduler = mock(EntityScheduler.class);
        World managed = mock(World.class);
        World unmanaged = mock(World.class);
        Location from = mock(Location.class);
        Location to = mock(Location.class);
        PlayerMoveEvent event = mock(PlayerMoveEvent.class);
        List<Runnable> operations = new CopyOnWriteArrayList<>();
        when(managed.getName()).thenReturn("world");
        when(unmanaged.getName()).thenReturn("survival");
        when(from.getWorld()).thenReturn(unmanaged);
        when(to.getWorld()).thenReturn(managed);
        when(player.getWorld()).thenReturn(managed);
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.isOnline()).thenReturn(true);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getScheduler()).thenReturn(scheduler);
        when(event.getPlayer()).thenReturn(player);
        when(event.getFrom()).thenReturn(from);
        when(event.getTo()).thenReturn(to);
        when(scheduler.execute(eq(plugin), any(Runnable.class), isNull(), eq(1L))).thenAnswer(invocation -> {
            operations.add(invocation.getArgument(1));
            return true;
        });

        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            bridge.onMove(event);
            assertEquals(1, operations.size());

            operations.getFirst().run();

            assertEquals(2, operations.size());
        }
        when(coordinator.isActive(bridge)).thenReturn(false);
        bridge.close();
    }

    @Test
    void leavesWeatherUnmanagedWhenNoStateIsConfigured() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-bridge-test"));
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, UUID.randomUUID(),
                new ManagedLobbyStore(temporary.resolve("weather")), coordinator,
                mock(InvocationController.class), 4);
        setConfig(bridge, ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));
        when(coordinator.isActive(bridge)).thenReturn(true);
        World world = mock(World.class);
        WeatherChangeEvent event = mock(WeatherChangeEvent.class);
        when(world.getName()).thenReturn("world");
        when(event.getWorld()).thenReturn(world);
        when(event.toWeatherState()).thenReturn(true);

        bridge.onWeather(event);

        verify(event, never()).setCancelled(true);
        when(coordinator.isActive(bridge)).thenReturn(false);
        bridge.close();
    }

    private static void setConfig(PaperManagedLobbyBridge bridge, ManagedLobbyConfig config)
            throws ReflectiveOperationException {
        setField(bridge, "config", config);
    }

    private static void setField(PaperManagedLobbyBridge bridge, String name, Object value)
            throws ReflectiveOperationException {
        Field field = PaperManagedLobbyBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        field.set(bridge, value);
    }

    private static Object field(PaperManagedLobbyBridge bridge, String name) throws ReflectiveOperationException {
        Field field = PaperManagedLobbyBridge.class.getDeclaredField(name);
        field.setAccessible(true);
        return field.get(bridge);
    }

    private static Object menuSession(UUID player) throws ReflectiveOperationException {
        Class<?> type = Class.forName(PaperManagedLobbyBridge.class.getName() + "$MenuSession");
        Constructor<?> constructor = type.getDeclaredConstructor(UUID.class, UUID.class,
                ManagedLobbyConfig.Menu.class, Inventory.class);
        constructor.setAccessible(true);
        return constructor.newInstance(player, UUID.randomUUID(),
                new ManagedLobbyConfig.Menu("test", 1, "Test", Map.of()), mock(Inventory.class));
    }
}
