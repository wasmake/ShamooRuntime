package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.AvoidDuplicateLiterals"})
class ManagedLobbyRequestTest {
    @Test
    void acceptsBoundedExecuteRequests() {
        UUID player = UUID.randomUUID();
        ManagedLobbyRequest request = ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "visibility",
                "player", player.toString(), "mode", "staff"));

        assertEquals("execute", request.operation());
        assertEquals(player, request.player());

        ManagedLobbyRequest portal = ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-create", "player", player.toString(),
                "id", "spawn", "destination", "survival", "priority", 10));
        assertEquals(10, portal.optionalInteger("priority", 0));
        ManagedLobbyRequest hiddenPortal = ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-create", "player", player.toString(), "id", "hidden"));
        assertFalse(hiddenPortal.optionalBoolean("visualize", false));
    }

    @Test
    void rejectsUnknownKeysOperationsAndFiles() {
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "status", "extra", true)));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "console")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "read", "file", "../server.properties")));
    }

    @Test
    void requiresAnEditorIdentityForDestructivePortalActions() {
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-remove", "id", "spawn")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-enable", "id", "spawn")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "id", "spawn",
                "destination", "survival")));

        assertEquals("execute", ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-list")).operation());
    }

    @Test
    void acceptsDiscriminatedPortalDestinations() {
        UUID player = UUID.randomUUID();
        ManagedLobbyRequest server = ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "player", player.toString(),
                "id", "portal-survival", "type", "server", "target", "survival"));
        ManagedLobbyRequest menu = ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "player", player.toString(),
                "id", "portal-survival", "type", "menu", "target", "game-selector"));
        Map<String, Object> spawnRequest = new LinkedHashMap<>();
        spawnRequest.put("operation", "execute");
        spawnRequest.put("action", "portal-destination");
        spawnRequest.put("player", player.toString());
        spawnRequest.put("id", "portal-survival");
        spawnRequest.put("type", "spawn");
        ManagedLobbyRequest spawn = ManagedLobbyRequest.parse(spawnRequest);

        assertEquals("server", server.text("type"));
        assertEquals("survival", server.text("target"));
        assertEquals("menu", menu.text("type"));
        assertEquals("game-selector", menu.text("target"));
        assertEquals("spawn", spawn.text("type"));
        assertNull(spawn.optionalText("target"));
    }

    @Test
    void rejectsMalformedPortalDestinations() {
        UUID player = UUID.randomUUID();
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "player", player.toString(),
                "id", "portal-survival", "destination", "survival")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "player", player.toString(),
                "id", "portal-survival", "type", "server")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "player", player.toString(),
                "id", "portal-survival", "type", "menu")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "player", player.toString(),
                "id", "portal-survival", "type", "spawn", "target", "survival")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "portal-destination", "player", player.toString(),
                "id", "portal-survival", "type", "command", "target", "survival")));

        Map<String, Object> nullSpawnTarget = new LinkedHashMap<>();
        nullSpawnTarget.put("operation", "execute");
        nullSpawnTarget.put("action", "portal-destination");
        nullSpawnTarget.put("player", player.toString());
        nullSpawnTarget.put("id", "portal-survival");
        nullSpawnTarget.put("type", "spawn");
        nullSpawnTarget.put("target", null);
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(nullSpawnTarget));

        Map<String, Object> nullCreateOption = new LinkedHashMap<>();
        nullCreateOption.put("operation", "execute");
        nullCreateOption.put("action", "portal-create");
        nullCreateOption.put("player", player.toString());
        nullCreateOption.put("id", "portal-survival");
        nullCreateOption.put("destination", null);
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(nullCreateOption));
    }

    @Test
    void optionalsAcceptOmissionButRejectExplicitNull() {
        ManagedLobbyRequest read = ManagedLobbyRequest.parse(Map.of("operation", "read"));
        ManagedLobbyRequest write = ManagedLobbyRequest.parse(Map.of(
                "operation", "write", "file", "config.yml", "content", "worlds: []\n"));
        assertNull(read.optionalText("file"));
        assertTrue(write.optionalBoolean("reload", true));

        Map<String, Object> nullReadFile = new LinkedHashMap<>();
        nullReadFile.put("operation", "read");
        nullReadFile.put("file", null);
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(nullReadFile));

        Map<String, Object> nullWriteReload = new LinkedHashMap<>();
        nullWriteReload.put("operation", "write");
        nullWriteReload.put("file", "config.yml");
        nullWriteReload.put("content", "worlds: []\n");
        nullWriteReload.put("reload", null);
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(nullWriteReload));
    }

    @Test
    void rejectsNonCanonicalPlayerUuids() {
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "spawn", "player", "1-1-1-1-1")));
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyRequest.parse(Map.of(
                "operation", "execute", "action", "spawn", "player",
                UUID.randomUUID().toString().toUpperCase(java.util.Locale.ROOT))));
    }
}
