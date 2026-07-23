package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class ResourceRegistryTest {
    @Test
    void cleansOneOwnerInReverseOrderAndIsIdempotent() {
        ResourceRegistry registry = new ResourceRegistry();
        PluginId owner = new PluginId("owner");
        PluginId other = new PluginId("other");
        List<String> closed = new ArrayList<>();
        registry.register(owner, ResourceCategory.LISTENER, "first", () -> closed.add("first"));
        registry.register(other, ResourceCategory.COMMAND, "other", () -> closed.add("other"));
        registry.register(owner, ResourceCategory.TIMER, "last", () -> closed.add("last"));

        ResourceCleanupReport report = registry.cleanup(owner);
        assertTrue(report.clean());
        assertEquals(List.of("last", "first"), closed);
        assertEquals(1, registry.size());
        assertEquals(0, registry.cleanup(owner).attempted());
    }

    @Test
    void aggregatesErrorsAndRetainsLeaksForRetry() {
        ResourceRegistry registry = new ResourceRegistry();
        PluginId owner = new PluginId("leaky");
        int[] attempts = {0};
        registry.register(owner, ResourceCategory.WATCHER, "watcher", () -> {
            int attempt = attempts[0];
            attempts[0]++;
            if (attempt == 0) {
                throw new IllegalStateException("busy");
            }
        });

        ResourceCleanupReport failed = registry.cleanup(owner);
        assertFalse(failed.clean());
        assertEquals(1, failed.errors().size());
        assertEquals(1, failed.leaked().size());
        assertTrue(registry.cleanup(owner).clean());
        assertTrue(registry.snapshot(owner).isEmpty());
    }

    @Test
    void supportsEveryAdministrativeResourceCategory() {
        ResourceRegistry registry = new ResourceRegistry();
        PluginId owner = new PluginId("categories");
        for (ResourceCategory category : ResourceCategory.values()) {
            registry.register(owner, category, category.name(), () -> { });
        }
        assertEquals(Set.copyOf(List.of(ResourceCategory.values())), registry.snapshot(owner).stream()
                .map(ResourceRegistration::category).collect(java.util.stream.Collectors.toUnmodifiableSet()));
    }
}
