package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import org.bukkit.Material;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.UnitTestContainsTooManyAsserts"})
class PaperUiDescriptorTest {
    @Test
    void validatesInventorySlotsAndItemActions() {
        PaperUiDescriptor.InventoryDefinition inventory = PaperUiDescriptor.inventory(Map.of(
                "kind", "inventory",
                "rows", 2,
                "title", "Menu",
                "protected", true,
                "slots", List.of(Map.of("slot", 4, "item", item()))), material -> material != Material.WATER);

        assertEquals(2, inventory.rows());
        assertEquals(Material.DIAMOND, inventory.slots().getFirst().item().material());
        assertEquals(1, inventory.slots().getFirst().item().actions().size());
        assertTrue(inventory.slots().getFirst().item().actions().values()
                .iterator().next().preventDefault());
    }

    @Test
    void rejectsInvalidRowsSlotsAmountsAndUnknownKeys() {
        assertThrows(IllegalArgumentException.class, () -> PaperUiDescriptor.inventory(Map.of(
                "kind", "inventory", "rows", 0, "title", "Bad", "protected", true,
                "slots", List.of()), material -> material != Material.WATER));
        assertThrows(IllegalArgumentException.class, () -> PaperUiDescriptor.inventory(Map.of(
                "kind", "inventory", "rows", 1, "title", "Bad", "protected", true,
                "slots", List.of(Map.of("slot", 9, "item", item()))), material -> material != Material.WATER));
        assertThrows(IllegalArgumentException.class, () -> PaperUiDescriptor.item(Map.of(
                "kind", "item", "material", "DIAMOND", "amount", 100, "lore", List.of()),
                material -> material != Material.WATER));
        assertThrows(IllegalArgumentException.class, () -> PaperUiDescriptor.item(Map.of(
                "kind", "item", "material", "DIAMOND", "amount", 1, "lore", List.of(),
                "unsafe", new Object()), material -> material != Material.WATER));
        assertThrows(IllegalArgumentException.class, () -> PaperUiDescriptor.item(Map.of(
                "kind", "item", "material", "WATER", "amount", 1, "lore", List.of()),
                material -> material != Material.WATER));
    }

    private static Map<String, Object> item() {
        dev.shamoo.runtime.core.ScriptCallback callback = values -> CompletableFuture.completedFuture(null);
        return Map.of("kind", "item", "material", "diamond", "amount", 1, "lore", List.of("Lore"),
                "name", "Name", "actions", Map.of("left", callback, "preventDefault", true));
    }
}
