package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.PlatformOperationResult;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.ScriptCallback;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import org.bukkit.Server;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.InventoryView;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;
import org.mockito.MockedConstruction;

@SuppressWarnings({"deprecation", "PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.AvoidDuplicateLiterals", "PMD.CloseResource"})
class PaperUiBridgeTest {
    @Test
    void protectedInventoryCancelsClicksAndDragsAndClosesViewers() {
        Fixture fixture = fixture();
        AtomicReference<PaperUiBridge.InventoryRegistration> registration = new AtomicReference<>();
        fixture.contexts().executeCommand(fixture.player(), "menu", "", Map.of(), Map.of(), context -> {
            return fixture.bridge().openInventory(new PluginId("fixture"),
                    (String) context.get("token"), inventory(true, List.of())).thenAccept(result -> {
                        assertTrue(result.value());
                        registration.set((PaperUiBridge.InventoryRegistration) result.resource());
                    });
        }).toCompletableFuture().join();
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(fixture.inventory());
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getView()).thenReturn(view);
        when(click.getRawSlot()).thenReturn(10);
        InventoryDragEvent drag = mock(InventoryDragEvent.class);
        when(drag.getView()).thenReturn(view);

        fixture.bridge().onInventoryClick(click);
        fixture.bridge().onInventoryDrag(drag);

        verify(click).setCancelled(true);
        verify(drag).setCancelled(true);
        registration.get().close();
        verify(fixture.player()).closeInventory();
        assertEquals(0, fixture.bridge().inventoryCount());
        fixture.bridge().close();
    }

    @Test
    void topSlotActionGetsTemporaryDataOnlyContextAndHonorsPreventDefault() {
        Fixture fixture = fixture();
        ItemMeta itemMeta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(itemMeta.getPersistentDataContainer()).thenReturn(data);
        AtomicReference<String> actionId = new AtomicReference<>();
        doAnswer(invocation -> {
            actionId.set(invocation.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(
                invocation -> actionId.get());
        AtomicInteger invoked = new AtomicInteger();
        AtomicReference<Map<String, Object>> actionContext = new AtomicReference<>();
        dev.shamoo.runtime.core.ScriptCallback callback = values -> {
            invoked.incrementAndGet();
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) values.getFirst();
            actionContext.set(context);
            assertTrue(fixture.contexts().reply((String) context.get("token"), "clicked")
                    .toCompletableFuture().join());
            return CompletableFuture.completedFuture(null);
        };
        Map<String, Object> item = Map.of("kind", "item", "material", "DIAMOND", "amount", 1,
                "lore", List.of(), "actions", Map.of("left", callback, "preventDefault", true));
        AtomicReference<PaperUiBridge.InventoryRegistration> registration = new AtomicReference<>();
        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class,
                (stack, constructionContext) -> {
                    when(stack.getItemMeta()).thenReturn(itemMeta);
                    when(stack.hasItemMeta()).thenReturn(true);
                    when(stack.setItemMeta(itemMeta)).thenReturn(true);
                })) {
            fixture.contexts().executeCommand(fixture.player(), "menu", "", Map.of(), Map.of(), context -> {
                return fixture.bridge().openInventory(new PluginId("fixture"), (String) context.get("token"),
                        inventory(false, List.of(Map.of("slot", 4, "item", item)))).thenAccept(result ->
                            registration.set((PaperUiBridge.InventoryRegistration) result.resource()));
            }).toCompletableFuture().join();
            assertEquals(1, construction.constructed().size());
            ItemStack stack = construction.constructed().getFirst();
            when(fixture.inventory().getItem(4)).thenReturn(stack);
        }
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(fixture.inventory());
        InventoryClickEvent click = mock(InventoryClickEvent.class);
        when(click.getView()).thenReturn(view);
        when(click.getRawSlot()).thenReturn(4);
        when(click.isLeftClick()).thenReturn(true);
        when(click.getWhoClicked()).thenReturn(fixture.player());
        ItemStack actionStack = fixture.inventory().getItem(4);
        when(click.getCurrentItem()).thenReturn(actionStack);

        fixture.bridge().onInventoryClick(click);

        assertEquals(1, invoked.get());
        assertEquals(java.util.Set.of("token", "sender", "action", "slot", "item"),
                actionContext.get().keySet());
        assertEquals(Map.of("material", "DIAMOND", "amount", 1), actionContext.get().get("item"));
        verify(click).setCancelled(true);
        verify(fixture.player()).sendMessage(any(Component.class));

        when(click.getCurrentItem()).thenReturn(mock(ItemStack.class));
        fixture.bridge().onInventoryClick(click);
        assertEquals(1, invoked.get(), "a replaced slot must not invoke its stale action");
        when(click.getRawSlot()).thenReturn(5);
        when(click.getCurrentItem()).thenReturn(actionStack);
        fixture.bridge().onInventoryClick(click);
        assertEquals(1, invoked.get(), "a moved action item must not invoke from a different slot");

        registration.get().close();
        fixture.bridge().onInventoryClick(click);
        assertEquals(1, invoked.get());
        fixture.bridge().close();
    }

    @Test
    void givenActionItemBecomesInertWhenItsRegistrationCloses() {
        Fixture fixture = fixture();
        ItemMeta itemMeta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(itemMeta.getPersistentDataContainer()).thenReturn(data);
        AtomicReference<String> actionId = new AtomicReference<>();
        doAnswer(invocation -> {
            actionId.set(invocation.getArgument(2));
            return null;
        }).when(data).set(any(NamespacedKey.class), eq(PersistentDataType.STRING), anyString());
        when(data.get(any(NamespacedKey.class), eq(PersistentDataType.STRING))).thenAnswer(
                invocation -> actionId.get());
        AtomicInteger invoked = new AtomicInteger();
        AtomicReference<Map<String, Object>> actionContext = new AtomicReference<>();
        dev.shamoo.runtime.core.ScriptCallback callback = values -> {
            invoked.incrementAndGet();
            @SuppressWarnings("unchecked")
            Map<String, Object> context = (Map<String, Object>) values.getFirst();
            actionContext.set(context);
            return CompletableFuture.completedFuture(null);
        };
        Map<String, Object> item = Map.of("kind", "item", "material", "EMERALD", "amount", 2,
                "lore", List.of(), "actions", Map.of("right", callback, "preventDefault", false));
        AtomicReference<PaperUiBridge.ItemRegistration> registration = new AtomicReference<>();
        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(itemMeta);
            when(stack.hasItemMeta()).thenReturn(true);
            when(stack.setItemMeta(itemMeta)).thenReturn(true);
            when(stack.getMaxStackSize()).thenReturn(64);
            when(stack.getType()).thenReturn(org.bukkit.Material.EMERALD);
            when(stack.getAmount()).thenReturn(2);
        })) {
            fixture.contexts().executeCommand(fixture.player(), "give", "", Map.of(), Map.of(), context -> {
                return fixture.bridge().giveItem(new PluginId("fixture"),
                        (String) context.get("token"), item).thenAccept(result ->
                            registration.set((PaperUiBridge.ItemRegistration) result.resource()));
            }).toCompletableFuture().join();
            ItemStack stack = construction.constructed().getFirst();
            when(stack.getAmount()).thenReturn(1);
            PlayerInteractEvent event = mock(PlayerInteractEvent.class);
            when(event.getHand()).thenReturn(EquipmentSlot.HAND);
            when(event.getAction()).thenReturn(Action.RIGHT_CLICK_AIR);
            when(event.getItem()).thenReturn(stack);
            when(event.getPlayer()).thenReturn(fixture.player());

            fixture.bridge().onPlayerInteract(event);

            assertEquals(1, invoked.get());
            assertEquals(java.util.Set.of("token", "sender", "action", "item"), actionContext.get().keySet());
            assertEquals("right", actionContext.get().get("action"));
            assertEquals(Map.of("material", "EMERALD", "amount", 1), actionContext.get().get("item"));
            assertEquals(1, fixture.bridge().actionableItemCount());

            registration.get().close();
            fixture.bridge().onPlayerInteract(event);
            assertEquals(1, invoked.get());
            assertEquals(0, fixture.bridge().actionableItemCount());
        }
        fixture.bridge().close();
    }

    @Test
    void naturalInventoryCloseReleasesSlotCallbacksWithoutClosingTheSameViewAgain() {
        Fixture fixture = fixture();
        AtomicInteger closes = new AtomicInteger();
        ScriptCallback callback = new ScriptCallback() {
            @Override
            public java.util.concurrent.CompletionStage<Object> invoke(List<Object> arguments) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                closes.incrementAndGet();
            }
        };
        ItemMeta itemMeta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(itemMeta.getPersistentDataContainer()).thenReturn(data);
        Map<String, Object> item = Map.of("kind", "item", "material", "DIAMOND", "amount", 1,
                "lore", List.of(), "actions", Map.of("left", callback, "preventDefault", true));
        AtomicReference<PlatformOperationResult<Boolean>> result = new AtomicReference<>();
        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(itemMeta);
            when(stack.setItemMeta(itemMeta)).thenReturn(true);
        })) {
            fixture.contexts().executeCommand(fixture.player(), "menu", "", Map.of(), Map.of(), context ->
                    fixture.bridge().openInventory(new PluginId("fixture"), (String) context.get("token"),
                            inventory(false, List.of(Map.of("slot", 1, "item", item)))).thenApply(value -> {
                                result.set(value);
                                return value;
                            }))
                    .toCompletableFuture().join();
            assertEquals(1, construction.constructed().size());
        }
        ResourceRegistry resources = new ResourceRegistry();
        resources.register(new PluginId("fixture"), ResourceCategory.GENERIC, "inventory",
                result.get().resource());
        assertEquals(1, resources.size());
        InventoryView view = mock(InventoryView.class);
        when(view.getTopInventory()).thenReturn(fixture.inventory());
        InventoryCloseEvent event = mock(InventoryCloseEvent.class);
        when(event.getView()).thenReturn(view);

        fixture.bridge().onInventoryClose(event);

        assertEquals(1, closes.get());
        assertEquals(0, fixture.bridge().inventoryCount());
        assertEquals(0, resources.size());
        verify(fixture.player(), never()).closeInventory();
        fixture.bridge().close();
    }

    @Test
    void cancelledOpenAndUnexpectedGiveLeftoversReturnFalseWithoutResources() {
        Fixture fixture = fixture();
        when(fixture.player().openInventory(fixture.inventory())).thenReturn(null);
        AtomicReference<PlatformOperationResult<Boolean>> openResult = new AtomicReference<>();
        fixture.contexts().executeCommand(fixture.player(), "menu", "", Map.of(), Map.of(), context ->
                fixture.bridge().openInventory(new PluginId("fixture"), (String) context.get("token"),
                        inventory(false, List.of())).thenAccept(openResult::set)).toCompletableFuture().join();
        assertFalse(openResult.get().value());
        assertNull(openResult.get().resource());
        assertEquals(0, fixture.bridge().inventoryCount());

        ItemMeta itemMeta = mock(ItemMeta.class);
        PersistentDataContainer data = mock(PersistentDataContainer.class);
        when(itemMeta.getPersistentDataContainer()).thenReturn(data);
        AtomicInteger callbackCloses = new AtomicInteger();
        ScriptCallback callback = new ScriptCallback() {
            @Override
            public java.util.concurrent.CompletionStage<Object> invoke(List<Object> arguments) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                callbackCloses.incrementAndGet();
            }
        };
        AtomicReference<PlatformOperationResult<Boolean>> giveResult = new AtomicReference<>();
        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(itemMeta);
            when(stack.setItemMeta(itemMeta)).thenReturn(true);
            when(stack.getMaxStackSize()).thenReturn(64);
        })) {
            when(fixture.player().getInventory().addItem(any(ItemStack.class))).thenAnswer(invocation ->
                    new java.util.HashMap<>(Map.of(0, invocation.getArgument(0))));
            fixture.contexts().executeCommand(fixture.player(), "give", "", Map.of(), Map.of(), context ->
                    fixture.bridge().giveItem(new PluginId("fixture"), (String) context.get("token"),
                            Map.of("kind", "item", "material", "EMERALD", "amount", 1,
                                    "lore", List.of(), "actions",
                                    Map.of("left", callback, "preventDefault", true)))
                            .thenAccept(giveResult::set)).toCompletableFuture().join();
            assertEquals(1, construction.constructed().size());
        }
        assertFalse(giveResult.get().value());
        assertNull(giveResult.get().resource());
        assertEquals(0, fixture.bridge().actionableItemCount());
        assertEquals(1, callbackCloses.get());
        verify(fixture.player().getInventory()).setStorageContents(any(ItemStack[].class));
        fixture.bridge().close();
    }

    @Test
    void fullInventoryRejectsGrantWithoutSideEffectsOrActionResource() {
        Fixture fixture = fixture();
        ItemStack occupied = mock(ItemStack.class);
        when(occupied.getType()).thenReturn(org.bukkit.Material.STONE);
        when(occupied.isSimilar(any(ItemStack.class))).thenReturn(false);
        ItemStack[] contents = new ItemStack[36];
        java.util.Arrays.fill(contents, occupied);
        when(fixture.player().getInventory().getStorageContents()).thenReturn(contents);
        AtomicInteger callbackCloses = new AtomicInteger();
        ScriptCallback callback = new ScriptCallback() {
            @Override
            public java.util.concurrent.CompletionStage<Object> invoke(List<Object> values) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                callbackCloses.incrementAndGet();
            }
        };
        AtomicReference<PlatformOperationResult<Boolean>> result = new AtomicReference<>();
        ItemMeta itemMeta = mock(ItemMeta.class);
        when(itemMeta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(itemMeta);
            when(stack.setItemMeta(itemMeta)).thenReturn(true);
            when(stack.getMaxStackSize()).thenReturn(64);
            when(stack.getAmount()).thenReturn(1);
        })) {
            fixture.contexts().executeCommand(fixture.player(), "give", "", Map.of(), Map.of(), context ->
                    fixture.bridge().giveItem(new PluginId("fixture"), (String) context.get("token"),
                            Map.of("kind", "item", "material", "DIAMOND", "amount", 1,
                                    "lore", List.of(), "actions",
                                    Map.of("left", callback, "preventDefault", true)))
                            .thenAccept(result::set)).toCompletableFuture().join();
            assertEquals(1, construction.constructed().size());
        }

        assertFalse(result.get().value());
        assertNull(result.get().resource());
        assertEquals(0, fixture.bridge().actionableItemCount());
        assertEquals(1, callbackCloses.get());
        verify(fixture.player().getInventory(), never()).addItem(any(ItemStack.class));
        fixture.bridge().close();
    }

    @Test
    void similarPartialStackProvidesExactGrantCapacity() throws Exception {
        Fixture fixture = fixture();
        ItemStack partial = mock(ItemStack.class);
        when(partial.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(partial.getAmount()).thenReturn(63);
        ItemStack[] contents = new ItemStack[36];
        java.util.Arrays.fill(contents, mock(ItemStack.class));
        contents[4] = partial;
        for (ItemStack occupied : contents) {
            when(occupied.getType()).thenReturn(org.bukkit.Material.STONE);
        }
        when(partial.getType()).thenReturn(org.bukkit.Material.DIAMOND);
        when(fixture.player().getInventory().getStorageContents()).thenReturn(contents);
        AtomicReference<PlatformOperationResult<Boolean>> result = new AtomicReference<>();
        ItemMeta itemMeta = mock(ItemMeta.class);
        when(itemMeta.getPersistentDataContainer()).thenReturn(mock(PersistentDataContainer.class));
        try (MockedConstruction<ItemStack> construction = mockConstruction(ItemStack.class, (stack, context) -> {
            when(stack.getItemMeta()).thenReturn(itemMeta);
            when(stack.setItemMeta(itemMeta)).thenReturn(true);
            when(stack.getMaxStackSize()).thenReturn(64);
            when(stack.getAmount()).thenReturn(1);
            when(partial.isSimilar(stack)).thenReturn(true);
        })) {
            fixture.contexts().executeCommand(fixture.player(), "give", "", Map.of(), Map.of(), context ->
                    fixture.bridge().giveItem(new PluginId("fixture"), (String) context.get("token"),
                            Map.of("kind", "item", "material", "DIAMOND", "amount", 1,
                                    "lore", List.of()))
                            .thenAccept(result::set)).toCompletableFuture().join();
            assertEquals(1, construction.constructed().size());
        }

        assertTrue(result.get().value());
        assertNull(result.get().resource());
        verify(fixture.player().getInventory()).addItem(any(ItemStack.class));
        fixture.bridge().close();
    }

    private static Fixture fixture() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        Inventory inventory = mock(Inventory.class);
        Player player = mock(Player.class);
        PlayerInventory playerInventory = mock(PlayerInventory.class);
        when(plugin.getName()).thenReturn("ShamooRuntime");
        when(plugin.namespace()).thenReturn("shamooruntime");
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        when(server.createInventory(isNull(InventoryHolder.class), eq(9), any(Component.class)))
                .thenReturn(inventory);
        when(inventory.getSize()).thenReturn(9);
        when(inventory.getViewers()).thenReturn(List.of(player));
        when(player.getName()).thenReturn("Alex");
        when(player.getUniqueId()).thenReturn(UUID.randomUUID());
        when(player.getInventory()).thenReturn(playerInventory);
        when(player.openInventory(inventory)).thenReturn(mock(InventoryView.class));
        when(playerInventory.getMaxStackSize()).thenReturn(64);
        when(playerInventory.getStorageContents()).thenReturn(new ItemStack[36]);
        when(playerInventory.addItem(any(ItemStack.class))).thenReturn(new java.util.HashMap<>());
        PaperCommandContextBridge contexts = new PaperCommandContextBridge(server);
        return new Fixture(new PaperUiBridge(plugin, contexts, material -> material != org.bukkit.Material.WATER),
                contexts, player, inventory);
    }

    private static Map<String, Object> inventory(boolean protectedInventory, List<Object> slots) {
        return Map.of("kind", "inventory", "rows", 1, "title", "Menu",
                "protected", protectedInventory, "slots", slots);
    }

    private record Fixture(PaperUiBridge bridge, PaperCommandContextBridge contexts,
            Player player, Inventory inventory) {
    }
}
