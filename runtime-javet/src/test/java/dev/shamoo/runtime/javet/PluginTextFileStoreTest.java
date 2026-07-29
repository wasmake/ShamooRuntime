package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.UnitTestContainsTooManyAsserts"})
class PluginTextFileStoreTest {
    @TempDir
    Path temporary;

    @Test
    void seedsOnlyAllowedPluginDataAndPreservesExistingWrites() throws IOException {
        Path artifact = temporary.resolve("artifact");
        Files.createDirectories(artifact.resolve("data"));
        Files.writeString(artifact.resolve("data/config.yml"), "first");
        Files.writeString(artifact.resolve("index.js"), "bundle");
        Path persistent = temporary.resolve("persistent");

        PluginTextFileStore first = new PluginTextFileStore(
                persistent, artifact, List.of("data"), List.of("data"));
        assertEquals("first", first.read("data/config.yml"));
        assertFalse(Files.exists(persistent.resolve("index.js")));
        first.write("data/config.yml", "updated");

        Files.writeString(artifact.resolve("data/config.yml"), "new default");
        PluginTextFileStore reloaded = new PluginTextFileStore(
                persistent, artifact, List.of("data"), List.of("data"));
        assertEquals("updated", reloaded.read("data/config.yml"));
    }

    @Test
    void enforcesPathPolicyAndRejectsSymlinkAncestors() throws IOException {
        Path artifact = temporary.resolve("artifact");
        Files.createDirectories(artifact.resolve("data"));
        Files.writeString(artifact.resolve("data/config.yml"), "value");
        Path persistent = temporary.resolve("persistent");
        PluginTextFileStore store = new PluginTextFileStore(
                persistent, artifact, List.of("data"), List.of("data"));

        assertThrows(SecurityException.class, () -> store.read("../outside"));
        assertThrows(SecurityException.class, () -> store.write("other/config.yml", "value"));
        Files.createSymbolicLink(persistent.resolve("data/link"), temporary);
        assertThrows(SecurityException.class, () -> store.write("data/link/value", "value"));
        Files.createSymbolicLink(persistent.resolve("data/target.yml"), temporary.resolve("outside.yml"));
        assertThrows(SecurityException.class, () -> store.write("data/target.yml", "value"));

        Path linkedRoot = temporary.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, persistent);
        assertThrows(IOException.class, () -> new PluginTextFileStore(
                linkedRoot, artifact, List.of("data"), List.of("data")));
    }
}
