package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.AvoidDuplicateLiterals"})
class ManagedLobbyPortalIndexTest {
    @Test
    void resolvesChunkBoundariesByPriorityAndStableId() {
        ManagedLobbyPortalIndex.Portal lower = portal("lower", -1, 17, 1);
        ManagedLobbyPortalIndex.Portal higher = portal("higher", 0, 32, 10);
        ManagedLobbyPortalIndex index = new ManagedLobbyPortalIndex(List.of(lower, higher));

        assertEquals("higher", index.highest("world", 16, 64, 0).id());
        assertEquals("lower", index.highest("world", -1, 64, 0).id());
        assertNull(index.highest("other", 0, 64, 0));
        assertNull(index.highest("world", 40, 64, 0));
    }

    @Test
    void rejectsUnboundedPortalIndexes() {
        assertThrows(IllegalArgumentException.class, () -> new ManagedLobbyPortalIndex(List.of(
                portal("huge", 0, 2_000_000, 1))));
    }

    @Test
    void excludesDisabledPortalsFromEntryLookup() {
        ManagedLobbyPortalIndex.Portal enabled = portal("enabled", 0, 2, 1);
        ManagedLobbyPortalIndex.Portal disabled = new ManagedLobbyPortalIndex.Portal("disabled", "world",
                0, 60, -1, 2, 70, 1, false, null, 100, 1000, null,
                new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.SPAWN, null), false);

        assertEquals("enabled", new ManagedLobbyPortalIndex(List.of(disabled, enabled))
                .highest("world", 1, 64, 0).id());
        assertEquals("enabled", new ManagedLobbyPortalIndex(List.of(enabled))
                .highest("world", 2.999, 64.999, 0.999).id());
        assertThrows(IllegalArgumentException.class, () -> new ManagedLobbyPortalIndex.Portal("fractional", "world",
                0.5, 60, -1, 2, 70, 1, true, null, 1, 1000, null,
                new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.SPAWN, null), false));
    }

    private static ManagedLobbyPortalIndex.Portal portal(String id, double minimumX, double maximumX, int priority) {
        return new ManagedLobbyPortalIndex.Portal(id, "world", minimumX, 60, -1,
                maximumX, 70, 1, true, null, priority, 1000, null,
                new ManagedLobbyConfig.Action(ManagedLobbyConfig.ActionType.SPAWN, null), false);
    }
}
