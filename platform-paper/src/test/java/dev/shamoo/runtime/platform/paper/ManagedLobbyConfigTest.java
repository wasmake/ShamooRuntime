package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.AvoidDuplicateLiterals"})
class ManagedLobbyConfigTest {
    @Test
    void parsesOneOptionalSpawnAndCrossReferencedNativeActions() {
        Map<String, String> files = files();
        files.put("spawn.yml", "spawn:\n  configured: true\n  world: world\n  x: 1.5\n  y: 64\n"
                + "  z: -2\n  yaw: 90\n  pitch: 0\n");
        files.put("items.yml", "items:\n  - id: navigator\n    slot: 4\n    material: COMPASS\n"
                + "    name: '<gold>Navigator</gold>'\n    action: {type: menu, target: servers}\n");
        files.put("menus.yml", "menus:\n  - id: servers\n    rows: 1\n    title: Servers\n"
                + "    slots: []\n");
        files.put("config.yml", "worlds:\n  - name: world\njoin:\n  welcome-message: bienvenida\n"
                + "transfers:\n  cooldown-ms: 2500\n");
        files.put("messages.yml", "messages: {bienvenida: '<gold>Hola</gold>'}\n"
                + "titles: []\nsounds: []\nparticles: []\n");
        files.put("servers.yml", "servers:\n  - id: survival\n    enabled: true\n    target: Survival-1\n"
                + "    display-name: '<green>Supervivencia</green>'\n");
        files.put("portals.yml", "portals:\n  - id: survival\n    enabled: true\n    world: world\n"
                + "    min: {x: 0, y: 60, z: 0}\n    max: {x: 2, y: 70, z: 2}\n"
                + "    permission: lobby.survival\n    priority: 5\n    cooldown-ms: 1200\n"
                + "    destination: survival\n    visualize: true\n");

        ManagedLobbyConfig parsed = ManagedLobbyConfig.parse(files);

        assertEquals("world", parsed.spawn().world());
        assertEquals("servers", parsed.items().get("navigator").action().target());
        assertFalse(parsed.sidebar().enabled());
        assertEquals("spawn:\n  configured: false\n", ManagedLobbyConfig.encodeSpawn(null));
        assertEquals("Survival-1", parsed.transfers().enabled("survival").target());
        assertEquals(java.util.Set.of("survival"), parsed.transfers().allowed());
        assertEquals("survival", parsed.portals().getFirst().action().target());
        assertEquals("bienvenida", parsed.join().welcomeMessage());
        assertEquals("lobby.protection.bypass", parsed.protection().bypassPermission());
        assertEquals("lobby.visibility.staff", parsed.visibility().staffPermission());
        assertEquals(2500, parsed.defaultPortalCooldownMillis());

        files.put("portals.yml", ManagedLobbyConfig.encodePortals(parsed.portals()));
        ManagedLobbyPortalIndex.Portal roundTrip = ManagedLobbyConfig.parse(files).portals().getFirst();
        assertEquals("lobby.survival", roundTrip.permission());
        assertEquals(1200, roundTrip.cooldownMillis());
        assertEquals("survival", roundTrip.destination());
    }

    @Test
    void rejectsDuplicateKeysUnsafeTagsUnknownKeysAndDanglingActions() {
        Map<String, String> duplicate = files();
        duplicate.put("config.yml", "worlds: []\nworlds: []\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyConfig.parse(duplicate));

        Map<String, String> unsafe = files();
        unsafe.put("messages.yml", "messages: !!java/object:java.net.URL ['https://example.invalid']\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyConfig.parse(unsafe));

        Map<String, String> unknown = files();
        unknown.put("config.yml", "console-command: stop\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyConfig.parse(unknown));

        Map<String, String> dangling = files();
        dangling.put("items.yml", "items:\n  - id: bad\n    slot: 0\n    material: STONE\n"
                + "    action: {type: menu, target: missing}\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyConfig.parse(dangling));

        Map<String, String> incompleteSpawn = files();
        incompleteSpawn.put("spawn.yml", "spawn: {configured: true}\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyConfig.parse(incompleteSpawn));

        Map<String, String> duplicateItemSlot = files();
        duplicateItemSlot.put("items.yml", "items:\n  - id: first\n    slot: 0\n    material: STONE\n"
                + "  - id: second\n    slot: 0\n    material: COMPASS\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyConfig.parse(duplicateItemSlot));

        Map<String, String> fractionalPortal = files();
        fractionalPortal.put("portals.yml", "portals:\n  - id: fractional\n    world: world\n"
                + "    min: {x: 0.5, y: 60, z: 0}\n    max: {x: 2, y: 70, z: 2}\n");
        assertThrows(IllegalArgumentException.class, () -> ManagedLobbyConfig.parse(fractionalPortal));
    }

    static Map<String, String> files() {
        Map<String, String> result = new LinkedHashMap<>();
        result.put("config.yml", "worlds:\n  - name: world\n");
        result.put("messages.yml", "messages: {}\ntitles: []\nsounds: []\nparticles: []\n");
        result.put("items.yml", "items: []\n");
        result.put("menus.yml", "menus: []\n");
        result.put("scoreboard.yml", "sidebar: {enabled: false, title: Lobby, lines: []}\n");
        result.put("servers.yml", "servers: []\n");
        result.put("spawn.yml", "spawn:\n  configured: false\n");
        result.put("portals.yml", "portals: []\n");
        return result;
    }
}
