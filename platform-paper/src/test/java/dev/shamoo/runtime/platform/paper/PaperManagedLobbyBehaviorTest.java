package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.shamoo.runtime.core.InvocationController;
import io.papermc.paper.threadedregions.scheduler.EntityScheduler;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import java.lang.reflect.Field;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Server;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Item;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.block.BlockDispenseEvent;
import org.bukkit.event.block.BlockFertilizeEvent;
import org.bukkit.event.block.FluidLevelChangeEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryPickupItemEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerPickupArrowEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.world.PortalCreateEvent;
import org.bukkit.event.world.StructureGrowEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scoreboard.Scoreboard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.CloseResource", "PMD.AvoidDuplicateLiterals",
        "PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts"})
class PaperManagedLobbyBehaviorTest {
    @TempDir
    Path temporary;

    @Test
    void portalRevalidationRequiresCurrentTransitionAndUnchangedAction() {
        ManagedLobbyPortalIndex.Portal expected = portal(true,
                new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.SPAWN, null));
        ManagedLobbyPortalIndex.Portal changed = portal(true,
                new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.MENU, "selector"));
        ManagedLobbyPortalIndex.Portal disabled = portal(false, expected.action());
        PaperManagedLobbyBridge.PortalOccupancy first = new PaperManagedLobbyBridge.PortalOccupancy("portal", 1);
        PaperManagedLobbyBridge.PortalOccupancy second = new PaperManagedLobbyBridge.PortalOccupancy("portal", 2);

        assertTrue(PaperManagedLobbyBridge.portalActionStillValid(expected, expected, first, first, "world",
                1.75, 64.2, 1.2, true, true));
        assertFalse(PaperManagedLobbyBridge.portalActionStillValid(expected, changed, first, first, "world",
                1, 64, 1, true, true));
        assertFalse(PaperManagedLobbyBridge.portalActionStillValid(expected, disabled, first, first, "world",
                1, 64, 1, true, true));
        assertFalse(PaperManagedLobbyBridge.portalActionStillValid(expected, expected, first, null, "world",
                1, 64, 1, true, true));
        assertFalse(PaperManagedLobbyBridge.portalActionStillValid(expected, expected, first, second, "world",
                1, 64, 1, true, true));
        assertFalse(PaperManagedLobbyBridge.portalActionStillValid(expected, expected, first, first, "world",
                1, 64, 1, true, false));
        assertFalse(PaperManagedLobbyBridge.portalActionStillValid(expected, expected, first, first, "world",
                3, 64, 1, true, true));
    }

    @Test
    void deferredPortalActionRejectsWorldExitWithoutStartingCooldown() throws Exception {
        Fixture fixture = fixture(portalConfig());
        Player player = mock(Player.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        UUID playerId = UUID.randomUUID();
        List<Runnable> operations = new ArrayList<>();
        Location inside = location(fixture.managedWorld(), 1.2, 64.0, 1.2);
        AtomicReference<Location> current = new AtomicReference<>(inside);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenAnswer(ignored -> current.get().getWorld());
        when(player.getLocation()).thenAnswer(ignored -> current.get());
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(player.isOnline()).thenReturn(true);
        when(entityScheduler.execute(eq(fixture.plugin()), any(Runnable.class), any(Runnable.class), eq(1L)))
                .thenAnswer(invocation -> {
                    operations.add(invocation.getArgument(1));
                    return true;
        });
        PlayerMoveEvent movement = mock(PlayerMoveEvent.class);
        Location before = location(fixture.managedWorld(), -1, 64, 1);
        when(movement.getPlayer()).thenReturn(player);
        when(movement.getFrom()).thenReturn(before);
        when(movement.getTo()).thenReturn(inside);

        try (fixture; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(fixture.managedWorld());
            fixture.bridge().onMove(movement);
            assertEquals(1, operations.size());

            current.set(location(fixture.outsideWorld(), 10, 64, 10));
            operations.getFirst().run();

            verify(player, never()).teleportAsync(any(Location.class));
            assertTrue(cooldowns(fixture.bridge()).isEmpty());
        }
    }

    @Test
    void exitAndReentryOfSamePortalInvalidatesFirstDeferredAction() throws Exception {
        Fixture fixture = fixture(portalConfig());
        Player player = mock(Player.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        UUID playerId = UUID.randomUUID();
        List<Runnable> operations = new ArrayList<>();
        Location before = location(fixture.managedWorld(), -1, 64, 1);
        Location inside = location(fixture.managedWorld(), 1, 64, 1);
        Location outside = location(fixture.managedWorld(), 3, 64, 1);
        AtomicReference<Location> current = new AtomicReference<>(inside);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getName()).thenReturn("Player");
        when(player.getWorld()).thenReturn(fixture.managedWorld());
        when(player.getLocation()).thenAnswer(ignored -> current.get());
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(player.isOnline()).thenReturn(true);
        when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
        when(entityScheduler.execute(eq(fixture.plugin()), any(Runnable.class), any(Runnable.class), eq(1L)))
                .thenAnswer(invocation -> {
                    operations.add(invocation.getArgument(1));
                    return true;
                });
        PlayerMoveEvent firstEntry = movement(player, before, inside);
        PlayerMoveEvent exit = movement(player, inside, outside);
        PlayerMoveEvent secondEntry = movement(player, outside, inside);

        try (fixture; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(fixture.managedWorld());
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(player));
            fixture.bridge().onMove(firstEntry);
            current.set(outside);
            fixture.bridge().onMove(exit);
            current.set(inside);
            fixture.bridge().onMove(secondEntry);
            assertEquals(2, operations.size());

            operations.get(0).run();
            verify(player, never()).teleportAsync(any(Location.class));
            operations.get(1).run();
            verify(player, times(1)).teleportAsync(any(Location.class));
        }
    }

    @Test
    void cancelledMovementNeverChangesPortalOccupancy() throws Exception {
        Fixture fixture = fixture(portalConfig());
        PlayerMoveEvent movement = mock(PlayerMoveEvent.class);
        when(movement.isCancelled()).thenReturn(true);

        try (fixture) {
            fixture.bridge().onMove(movement);
            verify(movement, never()).getTo();
            assertTrue(occupiedPortals(fixture.bridge()).isEmpty());
        }
    }

    @Test
    void crossGenerationManagedMenuHolderProtectsClicksAndDragsFromBypassPlayers() throws Exception {
        UUID generation = UUID.randomUUID();
        Fixture fixture = fixture(ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()), generation);
        UUID playerId = UUID.randomUUID();
        Player player = mock(Player.class);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenReturn(fixture.managedWorld());
        when(player.hasPermission("lobby.protection.bypass")).thenReturn(true);
        Inventory top = mock(Inventory.class);
        PaperManagedLobbyBridge.MenuHolder holder = new PaperManagedLobbyBridge.MenuHolder(
                playerId, UUID.randomUUID(), UUID.randomUUID());
        when(top.getHolder(false)).thenReturn(holder);
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(top);
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getWhoClicked()).thenReturn(player);
        when(click.getView()).thenReturn(view);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getWhoClicked()).thenReturn(player);
        when(drag.getView()).thenReturn(view);

        try (fixture) {
            assertTrue(fixture.bridge().managedMenuInventory(top));
            fixture.bridge().onInventoryClick(click);
            fixture.bridge().onInventoryDrag(drag);
            verify(click).setCancelled(true);
            verify(drag).setCancelled(true);
        }
    }

    @Test
    void teleportTransitionsUseDedicatedManagedWorldPath() throws Exception {
        Map<String, String> files = ManagedLobbyConfigTest.files();
        files.put("config.yml", "join: {reset: false}\nworlds:\n  - name: world\n");
        ManagedLobbyConfig config = ManagedLobbyConfig.parse(files);
        Fixture fixture = fixture(config);
        Player player = mock(Player.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        org.bukkit.inventory.PlayerInventory inventory = mock(org.bukkit.inventory.PlayerInventory.class);
        InventoryView view = mock(InventoryView.class);
        Inventory top = mock(Inventory.class);
        List<Runnable> operations = new ArrayList<>();
        AtomicReference<World> currentWorld = new AtomicReference<>(fixture.outsideWorld());
        UUID playerId = UUID.randomUUID();
        Location outside = location(fixture.outsideWorld(), 0, 64, 0);
        Location inside = location(fixture.managedWorld(), 0, 64, 0);
        when(player.getUniqueId()).thenReturn(playerId);
        when(player.getWorld()).thenAnswer(ignored -> currentWorld.get());
        when(player.getLocation()).thenReturn(inside);
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(player.getInventory()).thenReturn(inventory);
        when(player.getOpenInventory()).thenReturn(view);
        when(player.isOnline()).thenReturn(true);
        when(inventory.getSize()).thenReturn(0);
        when(view.getTopInventory()).thenReturn(top);
        when(entityScheduler.execute(eq(fixture.plugin()), any(Runnable.class), isNull(), eq(1L)))
                .thenAnswer(invocation -> {
                    operations.add(invocation.getArgument(1));
                    return true;
                });
        PlayerTeleportEvent entering = mock(PlayerTeleportEvent.class);
        when(entering.getPlayer()).thenReturn(player);
        when(entering.getFrom()).thenReturn(outside);
        when(entering.getTo()).thenReturn(inside);
        PlayerTeleportEvent leaving = mock(PlayerTeleportEvent.class);
        when(leaving.getPlayer()).thenReturn(player);
        when(leaving.getFrom()).thenReturn(inside);
        when(leaving.getTo()).thenReturn(outside);

        try (fixture; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            fixture.bridge().onTeleport(entering);
            assertEquals(1, operations.size());
            assertTrue(occupiedPortals(fixture.bridge()).isEmpty());
            currentWorld.set(fixture.managedWorld());
            operations.getFirst().run();
            assertTrue(visibility(fixture.bridge()).containsKey(playerId));

            currentWorld.set(fixture.outsideWorld());
            fixture.bridge().onTeleport(leaving);
            assertFalse(visibility(fixture.bridge()).containsKey(playerId));
            assertEquals(1, operations.size());
        }

        assertEquals(PaperManagedLobbyBridge.ManagedWorldTransition.ENTER,
                PaperManagedLobbyBridge.managedWorldTransition(config, "survival", "world"));
        assertEquals(PaperManagedLobbyBridge.ManagedWorldTransition.LEAVE,
                PaperManagedLobbyBridge.managedWorldTransition(config, "world", "survival"));
        assertEquals(PaperManagedLobbyBridge.ManagedWorldTransition.NONE,
                PaperManagedLobbyBridge.managedWorldTransition(config, "world", "world"));
    }

    @Test
    void managedDeathOverridesOutsideRespawnAndJoinSchedulesPostTeleportSetup() throws Exception {
        ManagedLobbyConfig config = respawnConfig();
        Fixture fixture = fixture(config);
        Player player = mock(Player.class);
        EntityScheduler entityScheduler = mock(EntityScheduler.class);
        List<Runnable> operations = new ArrayList<>();
        when(player.getWorld()).thenReturn(fixture.managedWorld());
        when(player.getScheduler()).thenReturn(entityScheduler);
        when(player.isOnline()).thenReturn(true);
        when(entityScheduler.execute(eq(fixture.plugin()), any(Runnable.class), isNull(), eq(1L)))
                .thenAnswer(invocation -> {
                    operations.add(invocation.getArgument(1));
                    return true;
        });
        PlayerRespawnEvent respawn = mock(PlayerRespawnEvent.class);
        Location outsideRespawn = location(fixture.outsideWorld(), 0, 70, 0);
        when(respawn.getPlayer()).thenReturn(player);
        when(respawn.getRespawnLocation()).thenReturn(outsideRespawn);

        try (fixture; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(() -> Bukkit.getWorld("world")).thenReturn(fixture.managedWorld());
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
            fixture.bridge().onRespawn(respawn);
            verify(respawn).setRespawnLocation(any(Location.class));
            assertEquals(1, operations.size());

            when(player.getWorld()).thenReturn(fixture.outsideWorld());
            when(player.teleportAsync(any(Location.class))).thenReturn(CompletableFuture.completedFuture(true));
            PlayerJoinEvent join = mock(PlayerJoinEvent.class);
            when(join.getPlayer()).thenReturn(player);
            fixture.bridge().onJoin(join);
            verify(player).teleportAsync(any(Location.class));
            assertEquals(2, operations.size());
        }

        assertTrue(PaperManagedLobbyBridge.managedRespawn(config, "world", "survival"));
        assertTrue(PaperManagedLobbyBridge.managedRespawn(config, "survival", "world"));
        assertFalse(PaperManagedLobbyBridge.managedRespawn(config, "survival", "nether"));
    }

    @Test
    void leavingManagedWorldQueuesImmediateOtherViewerRefresh() throws Exception {
        Fixture fixture = fixture(ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));
        Player leaving = mock(Player.class);
        Player viewer = mock(Player.class);
        EntityScheduler leavingScheduler = mock(EntityScheduler.class);
        EntityScheduler viewerScheduler = mock(EntityScheduler.class);
        List<Runnable> leavingOperations = new ArrayList<>();
        List<Runnable> viewerOperations = new ArrayList<>();
        UUID leavingId = UUID.randomUUID();
        org.bukkit.inventory.PlayerInventory leavingInventory = mock(org.bukkit.inventory.PlayerInventory.class);
        InventoryView leavingView = mock(InventoryView.class);
        Inventory leavingTop = mock(Inventory.class);
        when(leaving.getUniqueId()).thenReturn(leavingId);
        when(leaving.getWorld()).thenReturn(fixture.outsideWorld());
        when(leaving.getScheduler()).thenReturn(leavingScheduler);
        when(leaving.getInventory()).thenReturn(leavingInventory);
        when(leaving.getOpenInventory()).thenReturn(leavingView);
        when(leavingView.getTopInventory()).thenReturn(leavingTop);
        when(viewer.getUniqueId()).thenReturn(UUID.randomUUID());
        when(viewer.getWorld()).thenReturn(fixture.managedWorld());
        when(viewer.getScheduler()).thenReturn(viewerScheduler);
        when(leavingScheduler.execute(eq(fixture.plugin()), any(Runnable.class), isNull(), eq(1L)))
                .thenAnswer(invocation -> {
                    leavingOperations.add(invocation.getArgument(1));
                    return true;
                });
        when(viewerScheduler.execute(eq(fixture.plugin()), any(Runnable.class), isNull(), eq(1L)))
                .thenAnswer(invocation -> {
                    viewerOperations.add(invocation.getArgument(1));
                    return true;
        });
        PlayerMoveEvent movement = mock(PlayerMoveEvent.class);
        Location from = location(fixture.managedWorld(), 0, 64, 0);
        Location to = location(fixture.outsideWorld(), 0, 64, 0);
        when(movement.getPlayer()).thenReturn(leaving);
        when(movement.getFrom()).thenReturn(from);
        when(movement.getTo()).thenReturn(to);

        try (fixture; MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of(leaving, viewer));
            fixture.bridge().onMove(movement);
            assertEquals(1, leavingOperations.size());
            leavingOperations.getFirst().run();
            assertEquals(1, viewerOperations.size());
        }
    }

    @Test
    void environmentalAndActorAwareMutationPathsUseCorrectBypassScope() throws Exception {
        Fixture fixture = fixture(ManagedLobbyConfig.parse(ManagedLobbyConfigTest.files()));
        Location managedLocation = location(fixture.managedWorld(), 0, 64, 0);
        Inventory source = mock(Inventory.class);
        Inventory destination = mock(Inventory.class);
        when(source.getLocation()).thenReturn(managedLocation);
        InventoryMoveItemEvent move = mock(InventoryMoveItemEvent.class);
        ItemStack plainItem = mock(ItemStack.class);
        when(move.getSource()).thenReturn(source);
        when(move.getDestination()).thenReturn(destination);
        when(move.getItem()).thenReturn(plainItem);
        InventoryPickupItemEvent pickup = mock(InventoryPickupItemEvent.class);
        when(pickup.getInventory()).thenReturn(source);
        Item dropped = mock(Item.class);
        when(dropped.getItemStack()).thenReturn(plainItem);
        when(pickup.getItem()).thenReturn(dropped);
        Block block = mock(Block.class);
        when(block.getWorld()).thenReturn(fixture.managedWorld());
        BlockDispenseEvent dispense = mock(BlockDispenseEvent.class);
        when(dispense.getBlock()).thenReturn(block);
        Player bypass = mock(Player.class);
        when(bypass.hasPermission("lobby.protection.bypass")).thenReturn(true);
        BlockFertilizeEvent fertilize = mock(BlockFertilizeEvent.class);
        when(fertilize.getBlock()).thenReturn(block);
        when(fertilize.getPlayer()).thenReturn(bypass);
        StructureGrowEvent structure = mock(StructureGrowEvent.class);
        when(structure.getWorld()).thenReturn(fixture.managedWorld());
        when(structure.getPlayer()).thenReturn(bypass);
        PortalCreateEvent portalCreate = mock(PortalCreateEvent.class);
        when(portalCreate.getWorld()).thenReturn(fixture.managedWorld());
        when(portalCreate.getEntity()).thenReturn(bypass);
        StructureGrowEvent actorlessStructure = mock(StructureGrowEvent.class);
        when(actorlessStructure.getWorld()).thenReturn(fixture.managedWorld());
        PortalCreateEvent actorlessPortal = mock(PortalCreateEvent.class);
        when(actorlessPortal.getWorld()).thenReturn(fixture.managedWorld());
        Player player = mock(Player.class);
        when(player.getWorld()).thenReturn(fixture.managedWorld());
        PlayerPickupArrowEvent arrowPickup = mock(PlayerPickupArrowEvent.class);
        when(arrowPickup.getPlayer()).thenReturn(player);
        FluidLevelChangeEvent fluid = mock(FluidLevelChangeEvent.class);
        when(fluid.getBlock()).thenReturn(block);

        try (fixture) {
            fixture.bridge().onInventoryMove(move);
            fixture.bridge().onInventoryPickup(pickup);
            fixture.bridge().onBlockDispense(dispense);
            fixture.bridge().onBlockFertilize(fertilize);
            fixture.bridge().onStructureGrow(structure);
            fixture.bridge().onPortalCreate(portalCreate);
            fixture.bridge().onStructureGrow(actorlessStructure);
            fixture.bridge().onPortalCreate(actorlessPortal);
            fixture.bridge().onPickupArrow(arrowPickup);
            fixture.bridge().onFluidLevelChange(fluid);
            verify(move).setCancelled(true);
            verify(pickup).setCancelled(true);
            verify(dispense).setCancelled(true);
            verify(fertilize, never()).setCancelled(true);
            verify(structure, never()).setCancelled(true);
            verify(portalCreate, never()).setCancelled(true);
            verify(actorlessStructure).setCancelled(true);
            verify(actorlessPortal).setCancelled(true);
            verify(arrowPickup).setCancelled(true);
            verify(fluid).setCancelled(true);
        }
    }

    @Test
    void rightClickTriggerScoreboardReclaimAndDuplicatePortalRulesAreExplicit() {
        assertTrue(PaperManagedLobbyBridge.itemTrigger(Action.RIGHT_CLICK_AIR));
        assertTrue(PaperManagedLobbyBridge.itemTrigger(Action.RIGHT_CLICK_BLOCK));
        assertFalse(PaperManagedLobbyBridge.itemTrigger(Action.LEFT_CLICK_AIR));
        assertFalse(PaperManagedLobbyBridge.itemTrigger(Action.LEFT_CLICK_BLOCK));
        Scoreboard managed = mock(Scoreboard.class);
        assertFalse(PaperManagedLobbyBridge.scoreboardNeedsReclaim(managed, managed));
        assertTrue(PaperManagedLobbyBridge.scoreboardNeedsReclaim(mock(Scoreboard.class), managed));
        assertThrows(IllegalArgumentException.class,
                () -> PaperManagedLobbyBridge.requirePortalCreateAvailable(portal(true,
                        new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.SPAWN, null)), "portal"));
        PaperManagedLobbyBridge.requirePortalCreateAvailable(null, "portal");
        assertEquals(1, PaperManagedLobbyBridge.secondsRemaining(1));
        assertEquals(2, PaperManagedLobbyBridge.secondsRemaining(1001));
    }

    private Fixture fixture(ManagedLobbyConfig config) throws Exception {
        return fixture(config, UUID.randomUUID());
    }

    private Fixture fixture(ManagedLobbyConfig config, UUID generation) throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler global = mock(GlobalRegionScheduler.class);
        PaperManagedLobbyCoordinator coordinator = mock(PaperManagedLobbyCoordinator.class);
        World managed = mock(World.class);
        World outside = mock(World.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getLogger()).thenReturn(Logger.getLogger("managed-lobby-behavior-test"));
        when(server.getGlobalRegionScheduler()).thenReturn(global);
        when(coordinator.isActive(any())).thenReturn(true);
        when(managed.getName()).thenReturn("world");
        when(outside.getName()).thenReturn("survival");
        PaperManagedLobbyBridge bridge = new PaperManagedLobbyBridge(plugin, generation,
                new ManagedLobbyStore(temporary.resolve(generation.toString())), coordinator,
                mock(InvocationController.class), 16);
        setField(bridge, "config", config);
        setField(bridge, "portalIndex", new ManagedLobbyPortalIndex(config.portals()));
        return new Fixture(bridge, plugin, coordinator, managed, outside);
    }

    private static ManagedLobbyConfig portalConfig() {
        Map<String, String> files = ManagedLobbyConfigTest.files();
        files.put("spawn.yml", "spawn: {configured: true, world: world, x: 0, y: 64, z: 0, yaw: 0, pitch: 0}\n");
        files.put("portals.yml", "portals:\n  - id: portal\n    enabled: true\n    world: world\n"
                + "    min: {x: 0, y: 60, z: 0}\n    max: {x: 2, y: 70, z: 2}\n"
                + "    cooldown-ms: 0\n    action: {type: spawn}\n");
        return ManagedLobbyConfig.parse(files);
    }

    private static ManagedLobbyConfig respawnConfig() {
        Map<String, String> files = ManagedLobbyConfigTest.files();
        files.put("config.yml", "join: {reset: false, teleport: true}\nworlds:\n  - name: world\n");
        files.put("spawn.yml", "spawn: {configured: true, world: world, x: 0, y: 64, z: 0, yaw: 0, pitch: 0}\n");
        return ManagedLobbyConfig.parse(files);
    }

    private static ManagedLobbyPortalIndex.Portal portal(boolean enabled, ManagedLobbyConfig.Action action) {
        return new ManagedLobbyPortalIndex.Portal("portal", "world", 0, 60, 0, 2, 70, 2,
                enabled, null, 1, 2500, null, action, false);
    }

    private static Location location(World world, double x, double y, double z) {
        Location location = mock(Location.class);
        when(location.getWorld()).thenReturn(world);
        when(location.getX()).thenReturn(x);
        when(location.getY()).thenReturn(y);
        when(location.getZ()).thenReturn(z);
        when(location.getBlockX()).thenReturn((int) Math.floor(x));
        when(location.getBlockY()).thenReturn((int) Math.floor(y));
        when(location.getBlockZ()).thenReturn((int) Math.floor(z));
        return location;
    }

    private static PlayerMoveEvent movement(Player player, Location from, Location to) {
        PlayerMoveEvent movement = mock(PlayerMoveEvent.class);
        when(movement.getPlayer()).thenReturn(player);
        when(movement.getFrom()).thenReturn(from);
        when(movement.getTo()).thenReturn(to);
        return movement;
    }

    @SuppressWarnings("unchecked")
    private static Map<Object, Long> cooldowns(PaperManagedLobbyBridge bridge) throws ReflectiveOperationException {
        return (Map<Object, Long>) field(bridge, "cooldowns");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, PaperManagedLobbyBridge.PortalOccupancy> occupiedPortals(
            PaperManagedLobbyBridge bridge)
            throws ReflectiveOperationException {
        return (Map<UUID, PaperManagedLobbyBridge.PortalOccupancy>) field(bridge, "occupiedPortals");
    }

    @SuppressWarnings("unchecked")
    private static Map<UUID, ManagedLobbyConfig.VisibilityMode> visibility(PaperManagedLobbyBridge bridge)
            throws ReflectiveOperationException {
        return (Map<UUID, ManagedLobbyConfig.VisibilityMode>) field(bridge, "visibility");
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

    private record Fixture(PaperManagedLobbyBridge bridge, JavaPlugin plugin,
            PaperManagedLobbyCoordinator coordinator, World managedWorld, World outsideWorld) implements AutoCloseable {
        @Override
        public void close() {
            when(coordinator.isActive(bridge)).thenReturn(false);
            bridge.close();
        }
    }
}
