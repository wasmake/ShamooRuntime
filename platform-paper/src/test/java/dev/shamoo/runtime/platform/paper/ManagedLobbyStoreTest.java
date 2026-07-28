package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.AvoidDuplicateLiterals"})
class ManagedLobbyStoreTest {
    @TempDir
    Path temporary;

    @Test
    void ensuresExactlyEightFilesAndWritesWithBackup() throws Exception {
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("shalobby"));

        store.ensure();
        assertEquals(java.util.List.of("config.yml", "messages.yml", "items.yml", "menus.yml", "scoreboard.yml",
                "servers.yml", "spawn.yml", "portals.yml"), ManagedLobbyStore.FILES);
        assertEquals(ManagedLobbyStore.FILES, store.readAll().keySet().stream().toList());
        assertEquals(8, store.readAll().size());
        assertTrue(store.read("messages.yml").contains("Bienvenido"));
        assertFalse(store.readAll().containsKey("effects.yml"));
        for (String file : ManagedLobbyStore.FILES) {
            try (var expected = getClass().getResourceAsStream("/managed-lobby/" + file)) {
                assertEquals(new String(java.util.Objects.requireNonNull(expected).readAllBytes(),
                        StandardCharsets.UTF_8), store.read(file), file);
            }
        }
        ManagedLobbyConfig config = ManagedLobbyConfig.parse(store.readAll());
        assertEquals(5, config.items().size());
        assertEquals(4, config.menus().size());
        assertEquals(6, config.transfers().servers().size());
        assertEquals(3, config.portals().size());
        assertTrue(config.portals().stream().noneMatch(ManagedLobbyPortalIndex.Portal::enabled));
        assertTrue(config.messages().containsKey("item-cooldown"));
        assertTrue(config.messages().containsKey("portal-cooldown"));
        assertNull(config.spawn());

        String previous = store.read("messages.yml");
        store.write("messages.yml", "messages:\n  hello: '<green>Hello</green>'\n");
        store.ensure();

        assertTrue(store.read("messages.yml").contains("hello"));
        assertEquals(previous, Files.readString(store.directory().resolve("messages.yml.bak")));
        try (var paths = Files.list(store.directory())) {
            assertFalse(paths.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
        }
    }

    @Test
    void rejectsUnknownPathsAndOversizedWrites() {
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("data"));

        assertThrows(IllegalArgumentException.class, () -> store.read("../config.yml"));
        assertThrows(IllegalArgumentException.class, () -> store.write("extra.yml", "{}"));
        assertThrows(IllegalArgumentException.class, () -> store.write("config.yml",
                "x".repeat(ManagedLobbyStore.MAX_FILE_BYTES + 1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagedLobbyStore(
                temporary.resolve("x".repeat(ManagedLobbyStore.MAX_DIRECTORY_CHARS + 1))));
    }

    @Test
    void rejectsSymbolicLinkAncestorsAndResolvesTheirRealLocation() throws Exception {
        Path actual = temporary.resolve("actual");
        Files.createDirectories(actual);
        Path link = temporary.resolve("linked");
        Files.createSymbolicLink(link, actual);

        assertEquals(actual.resolve("lobby"), ManagedLobbyStore.resolveExistingAncestors(link.resolve("lobby")));
        ManagedLobbyStore store = new ManagedLobbyStore(link.resolve("lobby"));
        assertThrows(java.io.IOException.class, store::ensure);
    }

    @Test
    void rejectsWritesFromStaleSharedSnapshots() throws Exception {
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("shared"));
        store.ensure();
        ManagedLobbyStore.Snapshot stale = store.snapshot();
        String updated = "messages:\n  current: '<green>Current</green>'\n";
        store.write("messages.yml", updated);

        assertThrows(IllegalStateException.class, () -> store.writeIfUnchanged(stale,
                "messages.yml", "messages:\n  stale: '<red>Stale</red>'\n"));
        assertEquals(updated, store.read("messages.yml"));
        AtomicBoolean applied = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> store.runAtVersion(stale.version(), () -> applied.set(true)));
        assertFalse(applied.get());
        store.runAtVersion(store.snapshot().version(), () -> applied.set(true));
        assertTrue(applied.get());

        ManagedLobbyStore.Snapshot external = store.snapshot();
        Files.writeString(store.directory().resolve("messages.yml"),
                "messages:\n  external: '<yellow>External</yellow>'\n");
        assertThrows(IllegalStateException.class, () -> store.writeIfUnchanged(external,
                "messages.yml", updated));
        AtomicBoolean activated = new AtomicBoolean();
        assertThrows(IllegalStateException.class, () -> store.runAtSnapshot(external, () -> activated.set(true)));
        assertFalse(activated.get());
    }

    @Test
    void invalidatesVersionAfterRenameEvenWhenDirectorySyncFails() throws Exception {
        AtomicBoolean failSync = new AtomicBoolean();
        ManagedLobbyStore store = new ManagedLobbyStore(temporary.resolve("sync-failure"), () -> {
            if (failSync.get()) {
                throw new IOException("injected directory sync failure");
            }
        });
        store.ensure();
        ManagedLobbyStore.Snapshot verified = store.snapshot();
        String replacement = "messages:\n  replaced: '<green>Replaced</green>'\n";

        failSync.set(true);
        assertThrows(IOException.class, () -> store.writeIfUnchanged(
                verified, "messages.yml", replacement));

        assertEquals(replacement, store.read("messages.yml"));
        assertThrows(IllegalStateException.class, () -> store.runAtVersion(verified.version(), () -> {
            throw new AssertionError("stale action must not run");
        }));
    }
}
