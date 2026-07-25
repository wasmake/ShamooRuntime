package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.ScriptCallback;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import org.bukkit.Material;

/** Validated, JVM-owned views of script inventory and item descriptors. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.AvoidLiteralsInIfCondition", "PMD.CloseResource"})
final class PaperUiDescriptor {
    private static final Set<String> INVENTORY_KEYS = Set.of(
            "kind", "rows", "title", "protected", "slots");
    private static final Set<String> SLOT_KEYS = Set.of("slot", "item");
    private static final Set<String> ITEM_KEYS = Set.of(
            "kind", "material", "amount", "lore", "name", "actions");
    private static final Set<String> REQUIRED_ITEM_KEYS = Set.of("kind", "material", "amount", "lore");
    private static final Set<String> ACTION_KEYS = Set.of("left", "right", "preventDefault");

    private PaperUiDescriptor() {
    }

    static InventoryDefinition inventory(Object descriptor) {
        return inventory(descriptor, Material::isItem);
    }

    static InventoryDefinition inventory(Object descriptor, Predicate<Material> itemMaterial) {
        Map<String, Object> value = PaperDataDescriptor.object(
                descriptor, "inventory descriptor", INVENTORY_KEYS, INVENTORY_KEYS);
        kind(value.get("kind"), "inventory", "inventory descriptor.kind");
        int rows = PaperDataDescriptor.integer(value.get("rows"), "inventory descriptor.rows");
        if (rows < 1 || rows > 6) {
            throw PaperDataDescriptor.invalid("inventory descriptor.rows", "must be between 1 and 6");
        }
        List<SlotDefinition> slots = new ArrayList<>();
        Set<Integer> occupied = new java.util.HashSet<>();
        List<Object> rawSlots = PaperDataDescriptor.array(value.get("slots"), "inventory descriptor.slots");
        for (int index = 0; index < rawSlots.size(); index++) {
            String path = "inventory descriptor.slots[" + index + ']';
            Map<String, Object> rawSlot = PaperDataDescriptor.object(
                    rawSlots.get(index), path, SLOT_KEYS, SLOT_KEYS);
            int slot = PaperDataDescriptor.integer(rawSlot.get("slot"), path + ".slot");
            if (slot < 0 || slot >= rows * 9 || !occupied.add(slot)) {
                throw PaperDataDescriptor.invalid(path + ".slot", "is out of range or duplicated");
            }
            slots.add(new SlotDefinition(slot, item(rawSlot.get("item"), itemMaterial)));
        }
        return new InventoryDefinition(rows, value.get("title"),
                PaperDataDescriptor.bool(value.get("protected"), "inventory descriptor.protected"), slots);
    }

    static ItemDefinition item(Object descriptor) {
        return item(descriptor, Material::isItem);
    }

    static ItemDefinition item(Object descriptor, Predicate<Material> itemMaterial) {
        Map<String, Object> value = PaperDataDescriptor.object(
                descriptor, "item descriptor", ITEM_KEYS, REQUIRED_ITEM_KEYS);
        kind(value.get("kind"), "item", "item descriptor.kind");
        String materialName = PaperDataDescriptor.text(
                value.get("material"), "item descriptor.material", false).toUpperCase(Locale.ROOT);
        Material material = Material.getMaterial(materialName);
        if (material == null || !itemMaterial.test(material) || material == Material.AIR
                || material == Material.CAVE_AIR
                || material == Material.VOID_AIR) {
            throw PaperDataDescriptor.invalid("item descriptor.material", "is not an item material");
        }
        int amount = PaperDataDescriptor.integer(value.get("amount"), "item descriptor.amount");
        if (amount < 1 || amount > 99) {
            throw PaperDataDescriptor.invalid("item descriptor.amount", "must be between 1 and 99");
        }
        List<Object> lore = PaperDataDescriptor.array(value.get("lore"), "item descriptor.lore");
        if (lore.size() > 256) {
            throw PaperDataDescriptor.invalid("item descriptor.lore", "has too many lines");
        }
        Object name = value.get("name");
        Map<ActionSide, ItemAction> actions = value.containsKey("actions")
                ? actions(value.get("actions")) : Map.of();
        return new ItemDefinition(material, amount, name, lore, actions);
    }

    private static Map<ActionSide, ItemAction> actions(Object descriptor) {
        Map<String, Object> value = PaperDataDescriptor.object(
                descriptor, "item descriptor.actions", ACTION_KEYS, Set.of());
        Map<ActionSide, ItemAction> result = new LinkedHashMap<>();
        boolean preventDefault = !value.containsKey("preventDefault")
                || PaperDataDescriptor.bool(value.get("preventDefault"), "item descriptor.actions.preventDefault");
        for (String name : List.of("left", "right")) {
            if (!value.containsKey(name)) {
                continue;
            }
            String path = "item descriptor.actions." + name;
            if (!(value.get(name) instanceof ScriptCallback callback)) {
                throw PaperDataDescriptor.invalid(path, "must be a script callback");
            }
            result.put(ActionSide.valueOf(name.toUpperCase(Locale.ROOT)),
                    new ItemAction(callback, preventDefault));
        }
        return Map.copyOf(result);
    }

    private static void kind(Object value, String expected, String path) {
        if (!expected.equals(PaperDataDescriptor.text(value, path, false))) {
            throw PaperDataDescriptor.invalid(path, "must be '" + expected + "'");
        }
    }

    record InventoryDefinition(int rows, Object title, boolean protectedInventory, List<SlotDefinition> slots) {
        InventoryDefinition {
            slots = List.copyOf(slots);
        }
    }

    record SlotDefinition(int slot, ItemDefinition item) {
    }

    record ItemDefinition(Material material, int amount, Object name, List<Object> lore,
            Map<ActionSide, ItemAction> actions) {
        ItemDefinition {
            lore = List.copyOf(lore);
            actions = Map.copyOf(actions);
        }

        Map<String, Object> data() {
            return Map.of("material", material.name(), "amount", amount);
        }
    }

    record ItemAction(ScriptCallback callback, boolean preventDefault) {
    }

    enum ActionSide { LEFT, RIGHT }
}
