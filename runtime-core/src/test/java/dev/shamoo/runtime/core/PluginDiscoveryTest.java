package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shamoo.runtime.protocol.ManifestCodec;
import dev.shamoo.runtime.protocol.PluginArtifactProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.AvoidDuplicateLiterals",
    "PMD.LiteralsFirstInComparisons"
})
class PluginDiscoveryTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void discoversSortedCandidatesWithSha256Inventory() throws IOException {
        createCandidate("bravo", "bravo");
        createCandidate("alpha", "alpha");
        PluginDiscoveryResult result = new PluginDiscovery(Duration.ZERO).discover(temporaryDirectory);
        assertTrue(result.errors().isEmpty());
        assertEquals(List.of(new PluginId("alpha"), new PluginId("bravo")),
                result.candidates().stream().map(InstalledPluginCandidate::pluginId).toList());
        assertEquals(PluginArtifactProtocol.REQUIRED_FILES,
                result.candidates().getFirst().checksums().keySet());
        assertEquals(64, result.candidates().getFirst().checksums()
                .get(PluginArtifactProtocol.MODULE_FILE).length());
    }

    @Test
    void rejectsStrictDescriptorErrorsAndAllDuplicateIdentities() throws IOException {
        Path first = createCandidate("one", "duplicate");
        createCandidate("two", "duplicate");
        Files.writeString(first.resolve(PluginArtifactProtocol.MANIFEST_FILE), descriptor("duplicate") + " trailing");
        PluginDiscoveryResult malformed = new PluginDiscovery(Duration.ZERO).discover(temporaryDirectory);
        assertEquals(1, malformed.candidates().size());
        assertEquals("descriptor_invalid", malformed.errors().getFirst().code());

        Files.writeString(first.resolve(PluginArtifactProtocol.MANIFEST_FILE), descriptor("duplicate"));
        PluginDiscoveryResult duplicate = new PluginDiscovery(Duration.ZERO).discover(temporaryDirectory);
        assertTrue(duplicate.candidates().isEmpty());
        assertEquals("duplicate_plugin_id", duplicate.errors().getFirst().code());
    }

    @Test
    void rejectsCandidateAndNestedSymlinks() throws IOException {
        Path outside = temporaryDirectory.resolveSibling("outside-" + System.nanoTime());
        Files.createDirectories(outside);
        try {
            Files.createSymbolicLink(temporaryDirectory.resolve("linked"), outside);
            Path candidate = createCandidate("safe", "safe");
            Files.createSymbolicLink(candidate.resolve("escape"), outside);
            PluginDiscoveryResult result = new PluginDiscovery(Duration.ZERO).discover(temporaryDirectory);
            assertTrue(result.candidates().isEmpty());
            assertEquals(2, result.errors().size());
            assertTrue(result.errors().stream().allMatch(error -> error.code().equals("symbolic_link_rejected")));
        } finally {
            Files.deleteIfExists(outside);
        }
    }

    @Test
    void rejectsFilesThatChangeInsideStabilityWindow() throws Exception {
        Path candidate = createCandidate("moving", "moving");
        Path source = candidate.resolve(PluginArtifactProtocol.MODULE_FILE);
        CountDownLatch started = new CountDownLatch(1);
        Thread writer = Thread.ofPlatform().start(() -> {
            try {
                started.countDown();
                Thread.sleep(30);
                Files.writeString(source, "changed", StandardCharsets.UTF_8);
            } catch (IOException | InterruptedException exception) {
                throw new IllegalStateException(exception);
            }
        });
        started.await();
        PluginDiscoveryResult result = new PluginDiscovery(Duration.ofMillis(100)).discover(temporaryDirectory);
        writer.join();
        assertTrue(result.candidates().isEmpty());
        assertEquals("candidate_unstable", result.errors().getFirst().code());
    }

    @Test
    void candidateUsesImmutableStagedSnapshotRatherThanLiveBundle() throws IOException {
        Path source = createCandidate("source", "snapshot");
        InstalledPluginCandidate candidate = new PluginDiscovery(Duration.ZERO)
                .discover(temporaryDirectory).candidates().getFirst();
        Files.writeString(source.resolve(PluginArtifactProtocol.MODULE_FILE), "mutated", StandardCharsets.UTF_8);
        Files.writeString(source.resolve(PluginArtifactProtocol.MANIFEST_FILE), descriptor("changed"));

        assertNotEquals(source, candidate.root());
        assertEquals("export default 'snapshot';",
                Files.readString(candidate.root().resolve(PluginArtifactProtocol.MODULE_FILE)));
        assertEquals("snapshot", candidate.descriptor().name());
    }

    @Test
    void rejectsMissingExtraAndNestedArtifactEntries() throws IOException {
        Path missing = createCandidate("missing", "missing");
        Files.delete(missing.resolve(PluginArtifactProtocol.SOURCE_MAP_FILE));
        Path extra = createCandidate("extra", "extra");
        Files.writeString(extra.resolve("README.md"), "extra");
        Path nested = createCandidate("nested", "nested");
        Files.createDirectory(nested.resolve("dist"));
        Files.writeString(nested.resolve("dist/other.js"), "export {};");

        PluginDiscoveryResult result = new PluginDiscovery(Duration.ZERO).discover(temporaryDirectory);

        assertTrue(result.candidates().isEmpty());
        assertEquals(3, result.errors().size());
        assertTrue(result.errors().stream().allMatch(error -> error.code().equals("invalid_candidate_layout")));
    }

    @Test
    void rejectsReservedRuntimePluginIdentity() {
        assertThrows(IllegalArgumentException.class, () -> TestCandidates.candidate("runtime"));
    }

    private Path createCandidate(String directory, String id) throws IOException {
        Path root = Files.createDirectory(temporaryDirectory.resolve(directory));
        Files.writeString(root.resolve(PluginArtifactProtocol.MANIFEST_FILE), descriptor(id));
        Files.writeString(root.resolve(PluginArtifactProtocol.MODULE_FILE), "export default '" + id + "';");
        Files.writeString(root.resolve(PluginArtifactProtocol.SOURCE_MAP_FILE),
                "{\"version\":3,\"sources\":[\"src/plugin.ts\"],\"mappings\":\"AAAA\"}");
        return root;
    }

    private static String descriptor(String id) {
        return new ManifestCodec().serialize(TestCandidates.candidate(id).descriptor());
    }
}
