package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.CloseNotifyingResource;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.PlatformOperationResult;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Predicate;
import java.util.logging.Level;
import net.kyori.adventure.text.Component;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.HumanEntity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;

/** Owns script-created Paper inventories and opaque actionable item registrations. */
@SuppressWarnings({"PMD.CloseResource", "PMD.AvoidFieldNameMatchingMethodName"})
public final class PaperUiBridge implements Listener, AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperCommandContextBridge contexts;
    private final PaperRichTextRenderer renderer;
    private final NamespacedKey actionKey;
    private final Predicate<org.bukkit.Material> itemMaterial;
    private final Map<Inventory, InventoryRegistration> inventories = new ConcurrentHashMap<>();
    private final Map<String, ItemRegistration> itemActions = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperUiBridge(JavaPlugin plugin, PaperCommandContextBridge contexts) {
        this(plugin, contexts, org.bukkit.Material::isItem);
    }

    PaperUiBridge(JavaPlugin plugin, PaperCommandContextBridge contexts,
            Predicate<org.bukkit.Material> itemMaterial) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.itemMaterial = Objects.requireNonNull(itemMaterial, "itemMaterial");
        renderer = new PaperRichTextRenderer(plugin, contexts::executeAction);
        actionKey = new NamespacedKey(plugin, "shamoo_action");
        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    public CompletionStage<PlatformOperationResult<Boolean>> openInventory(
            PluginId owner, String token, Object descriptor) {
        requireOpen();
        Objects.requireNonNull(owner, "owner");
        return contexts.scheduleActivePlayer(token, player -> openInventory(owner, player, descriptor))
                .thenApply(result -> result == null ? PlatformOperationResult.value(false) : result);
    }

    private PlatformOperationResult<Boolean> openInventory(PluginId owner, Player player, Object descriptor) {
        PaperUiDescriptor.InventoryDefinition definition = PaperUiDescriptor.inventory(descriptor, itemMaterial);
        InventoryRegistration registration = null;
        List<AutoCloseable> renderedCallbacks = new ArrayList<>();
        try {
            Component title = renderer.render(definition.title(), player, renderedCallbacks::add);
            Inventory inventory = plugin.getServer().createInventory(null, definition.rows() * 9, title);
            Map<Integer, SlotAction> actions = new HashMap<>();
            for (PaperUiDescriptor.SlotDefinition slot : definition.slots()) {
                String id = slot.item().actions().isEmpty() ? null : UUID.randomUUID().toString();
                inventory.setItem(slot.slot(), item(slot.item(), player, id, renderedCallbacks));
                if (id != null) {
                    actions.put(slot.slot(), new SlotAction(id, slot.item().data(), slot.item().actions()));
                }
            }
            registration = new InventoryRegistration(
                    this, owner, inventory, definition.protectedInventory(), actions, renderedCallbacks);
            inventories.put(inventory, registration);
            if (player.openInventory(inventory) == null) {
                registration.closeWithoutViewers();
                return PlatformOperationResult.value(false);
            }
            return PlatformOperationResult.owned(true, registration);
        } catch (RuntimeException | Error failure) {
            if (registration == null) {
                closeActions(definition);
                closeResources(renderedCallbacks);
            } else {
                registration.closeWithoutViewers();
            }
            throw failure;
        }
    }

    public CompletionStage<PlatformOperationResult<Boolean>> giveItem(
            PluginId owner, String token, Object descriptor) {
        requireOpen();
        Objects.requireNonNull(owner, "owner");
        return contexts.scheduleActivePlayer(token, player -> giveItem(owner, player, descriptor))
                .thenApply(result -> result == null ? PlatformOperationResult.value(false) : result);
    }

    private PlatformOperationResult<Boolean> giveItem(PluginId owner, Player player, Object descriptor) {
        PaperUiDescriptor.ItemDefinition definition = PaperUiDescriptor.item(descriptor, itemMaterial);
        String id = definition.actions().isEmpty() ? null : UUID.randomUUID().toString();
        Map<PaperUiDescriptor.ActionSide, PaperUiDescriptor.ItemAction> actions =
                new EnumMap<>(PaperUiDescriptor.ActionSide.class);
        actions.putAll(definition.actions());
        List<AutoCloseable> renderedCallbacks = new ArrayList<>();
        ItemRegistration registration = null;
        try {
            ItemStack stack = item(definition, player, id, renderedCallbacks);
            if (id != null || !renderedCallbacks.isEmpty()) {
                registration = new ItemRegistration(this, owner, id, actions, renderedCallbacks);
            }
            PlayerInventory inventory = player.getInventory();
            ItemStack[] contents = Objects.requireNonNull(
                    inventory.getStorageContents(), "player inventory storage contents");
            if (!hasCapacity(contents, inventory, stack)) {
                if (registration != null) {
                    registration.close();
                }
                return PlatformOperationResult.value(false);
            }
            ItemStack[] original = cloneContents(contents);
            Map<Integer, ItemStack> leftovers = inventory.addItem(stack);
            if (!leftovers.isEmpty()) {
                inventory.setStorageContents(original);
                if (registration != null) {
                    registration.close();
                }
                return PlatformOperationResult.value(false);
            }
            if (id != null) {
                itemActions.put(id, Objects.requireNonNull(registration, "actionable item registration"));
            }
            return registration == null
                    ? PlatformOperationResult.value(true)
                    : PlatformOperationResult.owned(true, registration);
        } catch (RuntimeException | Error failure) {
            if (registration == null) {
                actions.values().forEach(action -> action.callback().close());
                closeResources(renderedCallbacks);
            } else {
                registration.close();
            }
            throw failure;
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        InventoryRegistration registration = inventories.get(top);
        if (registration == null || registration.closed()) {
            return;
        }
        if (registration.protectedInventory()) {
            event.setCancelled(true);
        }
        int slot = event.getRawSlot();
        if (slot < 0 || slot >= top.getSize()) {
            return;
        }
        PaperUiDescriptor.ActionSide side = event.isLeftClick() ? PaperUiDescriptor.ActionSide.LEFT
                : event.isRightClick() ? PaperUiDescriptor.ActionSide.RIGHT : null;
        if (side == null) {
            return;
        }
        SlotAction slotAction = registration.actions().get(slot);
        PaperUiDescriptor.ItemAction action = slotAction == null ? null : slotAction.actions().get(side);
        if (action == null || !slotAction.id().equals(actionId(event.getCurrentItem()))) {
            return;
        }
        if (action.preventDefault()) {
            event.setCancelled(true);
        }
        Map<String, Object> context = new java.util.LinkedHashMap<>();
        context.put("action", side.name().toLowerCase(java.util.Locale.ROOT));
        context.put("slot", slot);
        context.put("item", slotAction.item());
        invoke(event.getWhoClicked(), action, context);
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryDrag(InventoryDragEvent event) {
        InventoryRegistration registration = inventories.get(event.getView().getTopInventory());
        if (registration != null && registration.protectedInventory() && !registration.closed()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = false)
    public void onInventoryClose(InventoryCloseEvent event) {
        InventoryRegistration registration = inventories.get(event.getView().getTopInventory());
        if (registration != null) {
            registration.closeWithoutViewers();
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onPlayerInteract(PlayerInteractEvent event) {
        if (event.getHand() != EquipmentSlot.HAND) {
            return;
        }
        PaperUiDescriptor.ActionSide side = side(event.getAction());
        if (side == null) {
            return;
        }
        ItemStack stack = event.getItem();
        if (stack == null || !stack.hasItemMeta()) {
            return;
        }
        String id = actionId(stack);
        ItemRegistration registration = id == null ? null : itemActions.get(id);
        if (registration == null || registration.closed()) {
            return;
        }
        PaperUiDescriptor.ItemAction action = registration.actions().get(side);
        if (action == null) {
            return;
        }
        if (action.preventDefault()) {
            event.setCancelled(true);
        }
        invoke(event.getPlayer(), action, Map.of(
                "action", side.name().toLowerCase(java.util.Locale.ROOT),
                "item", itemData(stack)));
    }

    int inventoryCount() {
        return inventories.size();
    }

    int actionableItemCount() {
        return itemActions.size();
    }

    private ItemStack item(PaperUiDescriptor.ItemDefinition definition, Player audience, String actionId,
            List<AutoCloseable> renderedCallbacks) {
        ItemStack stack = new ItemStack(definition.material(), definition.amount());
        ItemMeta meta = Objects.requireNonNull(stack.getItemMeta(), "item material has no metadata");
        if (definition.name() != null) {
            meta.displayName(renderer.render(definition.name(), audience, renderedCallbacks::add));
        }
        List<Component> lore = definition.lore().stream()
                .map(line -> renderer.render(line, audience, renderedCallbacks::add)).toList();
        meta.lore(lore);
        if (actionId != null) {
            meta.getPersistentDataContainer().set(actionKey, PersistentDataType.STRING, actionId);
        }
        if (!stack.setItemMeta(meta)) {
            throw PaperDataDescriptor.invalid("item descriptor", "metadata is not valid for the material");
        }
        return stack;
    }

    private String actionId(ItemStack stack) {
        return stack == null || !stack.hasItemMeta() ? null
                : stack.getItemMeta().getPersistentDataContainer().get(actionKey, PersistentDataType.STRING);
    }

    private static Map<String, Object> itemData(ItemStack stack) {
        return Map.of("material", stack.getType().name(), "amount", stack.getAmount());
    }

    private static boolean hasCapacity(ItemStack[] contents, PlayerInventory inventory, ItemStack stack) {
        int maximum = Math.min(inventory.getMaxStackSize(), stack.getMaxStackSize());
        if (maximum <= 0) {
            return false;
        }
        int remaining = stack.getAmount();
        for (ItemStack existing : contents) {
            if (existing == null || isAir(Objects.requireNonNull(existing.getType(), "stored item material"))) {
                remaining -= maximum;
            } else if (existing.isSimilar(stack)) {
                remaining -= Math.max(0, maximum - existing.getAmount());
            }
            if (remaining <= 0) {
                return true;
            }
        }
        return false;
    }

    private static boolean isAir(org.bukkit.Material material) {
        return material == org.bukkit.Material.AIR || material == org.bukkit.Material.CAVE_AIR
                || material == org.bukkit.Material.VOID_AIR;
    }

    @SuppressWarnings("PMD.UseVarargs")
    private static ItemStack[] cloneContents(ItemStack[] contents) {
        ItemStack[] copy = contents.clone();
        for (int index = 0; index < copy.length; index++) {
            if (copy[index] != null) {
                copy[index] = copy[index].clone();
            }
        }
        return copy;
    }

    private static void closeActions(PaperUiDescriptor.InventoryDefinition definition) {
        definition.slots().stream().map(PaperUiDescriptor.SlotDefinition::item)
                .flatMap(item -> item.actions().values().stream())
                .forEach(action -> action.callback().close());
    }

    private static void closeResources(List<? extends AutoCloseable> resources) {
        for (AutoCloseable resource : resources) {
            try {
                resource.close();
            } catch (RuntimeException failure) {
                throw failure;
            } catch (Exception failure) {
                throw new IllegalStateException("unable to close rendered callback", failure);
            }
        }
        resources.clear();
    }

    private void invoke(HumanEntity sender, PaperUiDescriptor.ItemAction action, Map<String, Object> context) {
        try {
            contexts.executeAction(sender, action.callback(), context);
        } catch (RuntimeException exception) {
            plugin.getLogger().log(Level.WARNING, "Script item action failed", exception);
        }
    }

    private static PaperUiDescriptor.ActionSide side(Action action) {
        return switch (action) {
            case LEFT_CLICK_AIR, LEFT_CLICK_BLOCK -> PaperUiDescriptor.ActionSide.LEFT;
            case RIGHT_CLICK_AIR, RIGHT_CLICK_BLOCK -> PaperUiDescriptor.ActionSide.RIGHT;
            default -> null;
        };
    }

    private void remove(InventoryRegistration registration) {
        inventories.remove(registration.inventory(), registration);
    }

    private void remove(ItemRegistration registration) {
        if (registration.id() != null) {
            itemActions.remove(registration.id(), registration);
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Paper UI bridge is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        HandlerList.unregisterAll(this);
        new ArrayList<>(inventories.values()).forEach(InventoryRegistration::close);
        new ArrayList<>(itemActions.values()).forEach(ItemRegistration::close);
        inventories.clear();
        itemActions.clear();
    }

    public static final class InventoryRegistration implements CloseNotifyingResource {
        private final PaperUiBridge bridge;
        private final PluginId owner;
        private final Inventory inventory;
        private final boolean protectedInventory;
        private final Map<Integer, SlotAction> actions;
        private final List<AutoCloseable> renderedCallbacks;
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Runnable> closeNotification = new AtomicReference<>();

        private InventoryRegistration(PaperUiBridge bridge, PluginId owner, Inventory inventory,
                boolean protectedInventory, Map<Integer, SlotAction> actions,
                List<AutoCloseable> renderedCallbacks) {
            this.bridge = bridge;
            this.owner = owner;
            this.inventory = inventory;
            this.protectedInventory = protectedInventory;
            this.actions = new ConcurrentHashMap<>(actions);
            this.renderedCallbacks = new ArrayList<>(renderedCallbacks);
        }

        PluginId owner() {
            return owner;
        }

        Inventory inventory() {
            return inventory;
        }

        boolean protectedInventory() {
            return protectedInventory;
        }

        Map<Integer, SlotAction> actions() {
            return actions;
        }

        boolean closed() {
            return closed.get();
        }

        @Override
        public void onClosed(Runnable notification) {
            Objects.requireNonNull(notification, "notification");
            if (!closeNotification.compareAndSet(null, notification)) {
                throw new IllegalStateException("inventory close notification is already registered");
            }
            if (closed.get()) {
                notifyClosed();
            }
        }

        @Override
        public void close() {
            close(true);
        }

        private void closeWithoutViewers() {
            close(false);
        }

        private void close(boolean closeViewers) {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            bridge.remove(this);
            new ArrayList<>(actions.values()).forEach(SlotAction::close);
            actions.clear();
            closeResources(renderedCallbacks);
            if (closeViewers) {
                bridge.contexts.closeInventoryViewers(new ArrayList<>(inventory.getViewers()));
            }
            notifyClosed();
        }

        private void notifyClosed() {
            Runnable notification = closeNotification.getAndSet(null);
            if (notification != null) {
                notification.run();
            }
        }
    }

    public static final class ItemRegistration implements AutoCloseable {
        private final PaperUiBridge bridge;
        private final PluginId owner;
        private final String id;
        private final Map<PaperUiDescriptor.ActionSide, PaperUiDescriptor.ItemAction> actions;
        private final List<AutoCloseable> renderedCallbacks;
        private final AtomicBoolean closed = new AtomicBoolean();

        private ItemRegistration(PaperUiBridge bridge, PluginId owner, String id,
                Map<PaperUiDescriptor.ActionSide, PaperUiDescriptor.ItemAction> actions,
                List<AutoCloseable> renderedCallbacks) {
            this.bridge = bridge;
            this.owner = owner;
            this.id = id;
            this.actions = new ConcurrentHashMap<>(actions);
            this.renderedCallbacks = new ArrayList<>(renderedCallbacks);
        }

        PluginId owner() {
            return owner;
        }

        String id() {
            return id;
        }

        Map<PaperUiDescriptor.ActionSide, PaperUiDescriptor.ItemAction> actions() {
            return actions;
        }

        boolean closed() {
            return closed.get();
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            bridge.remove(this);
            actions.values().forEach(action -> action.callback().close());
            actions.clear();
            closeResources(renderedCallbacks);
        }
    }

    private record SlotAction(String id, Map<String, Object> item,
            Map<PaperUiDescriptor.ActionSide, PaperUiDescriptor.ItemAction> actions) implements AutoCloseable {
        private SlotAction {
            Objects.requireNonNull(id, "id");
            item = Map.copyOf(item);
            actions = Map.copyOf(actions);
        }

        @Override
        public void close() {
            actions.values().forEach(action -> action.callback().close());
        }
    }
}
