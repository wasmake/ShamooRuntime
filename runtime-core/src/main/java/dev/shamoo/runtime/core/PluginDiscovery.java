package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.ManifestCodec;
import dev.shamoo.runtime.protocol.PluginDescriptor;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Secure, deterministic discovery of directory-based plugin installations. */
public final class PluginDiscovery implements PluginStager {
    public static final String DESCRIPTOR_FILE = "shamoo-plugin.json";
    private static final String STAGING_DIRECTORY = ".shamoo-staging";
    private final ManifestCodec codec;
    private final Duration stabilityWindow;

    public PluginDiscovery(Duration stabilityWindow) {
        this(new ManifestCodec(), stabilityWindow);
    }

    PluginDiscovery(ManifestCodec codec, Duration stabilityWindow) {
        this.codec = Objects.requireNonNull(codec, "codec");
        this.stabilityWindow = Objects.requireNonNull(stabilityWindow, "stabilityWindow");
        if (stabilityWindow.isNegative()) {
            throw new IllegalArgumentException("stabilityWindow must not be negative");
        }
    }

    public PluginDiscoveryResult discover(Path pluginsDirectory) {
        Objects.requireNonNull(pluginsDirectory, "pluginsDirectory");
        Map<PluginId, InstalledPluginCandidate> candidates = new HashMap<>();
        List<PluginDiscoveryError> errors = new ArrayList<>();
        Map<PluginId, Path> identities = new HashMap<>();
        Path root = pluginsDirectory.toAbsolutePath().normalize();
        try {
            rejectLink(root);
            if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) {
                throw discovery("plugins_directory_invalid", root, "plugins directory is not a directory", null);
            }
            Path stagingRoot = root.resolve(STAGING_DIRECTORY);
            Files.createDirectories(stagingRoot);
            List<Path> entries = list(root).stream()
                    .filter(entry -> !entry.equals(stagingRoot))
                    .toList();
            for (Path entry : entries) {
                try {
                    InstalledPluginCandidate candidate = snapshot(entry, stagingRoot);
                    Path duplicate = identities.putIfAbsent(candidate.pluginId(), entry);
                    if (duplicate != null) {
                        candidates.remove(candidate.pluginId());
                        errors.add(discovery("duplicate_plugin_id", entry,
                                "duplicate plugin id " + candidate.pluginId() + " also found at " + duplicate, null));
                    } else {
                        candidates.put(candidate.pluginId(), candidate);
                    }
                } catch (PluginDiscoveryError error) {
                    errors.add(error);
                }
            }
        } catch (IOException exception) {
            errors.add(discovery("plugins_directory_unreadable", root,
                    "could not read plugins directory", exception));
        } catch (PluginDiscoveryError error) {
            errors.add(error);
        }
        List<InstalledPluginCandidate> ordered = new ArrayList<>(candidates.values());
        ordered.sort(Comparator.comparing(candidate -> candidate.pluginId().value()));
        return new PluginDiscoveryResult(ordered, errors);
    }

    @Override
    public InstalledPluginCandidate stage(Path source, Path stagingRoot) throws IOException {
        Path root = stagingRoot.toAbsolutePath().normalize();
        rejectLink(root);
        Files.createDirectories(root);
        return snapshot(source.toAbsolutePath().normalize(), root);
    }

    private InstalledPluginCandidate snapshot(Path candidateRoot, Path stagingRoot) throws IOException {
        rejectLink(candidateRoot);
        if (!Files.isDirectory(candidateRoot, LinkOption.NOFOLLOW_LINKS)) {
            throw discovery("candidate_not_directory", candidateRoot, "plugin candidate is not a directory", null);
        }
        Path canonicalRoot = candidateRoot.toRealPath(LinkOption.NOFOLLOW_LINKS);
        Map<String, FileSnapshot> first = inventory(canonicalRoot);
        waitForStability(candidateRoot);
        Map<String, FileSnapshot> second = inventory(canonicalRoot);
        if (!stamps(first).equals(stamps(second))) {
            throw discovery("candidate_unstable", candidateRoot, "plugin files changed during discovery", null);
        }
        FileSnapshot descriptorSnapshot = second.get(DESCRIPTOR_FILE);
        if (descriptorSnapshot == null) {
            throw discovery("descriptor_missing", canonicalRoot.resolve(DESCRIPTOR_FILE),
                    "plugin descriptor is missing", null);
        }
        Path temporary = Files.createTempDirectory(stagingRoot, ".snapshot-");
        try {
            String json = new String(descriptorSnapshot.content(), StandardCharsets.UTF_8);
            PluginDescriptor descriptor = codec.parse(json);
            Map<String, String> checksums = new LinkedHashMap<>();
            for (Map.Entry<String, FileSnapshot> entry : second.entrySet()) {
                Path destination = temporary.resolve(entry.getKey()).normalize();
                if (!destination.startsWith(temporary)) {
                    throw discovery("candidate_path_escape", candidateRoot,
                            "plugin path escapes staged snapshot", null);
                }
                Files.createDirectories(Objects.requireNonNull(destination.getParent(), "destination parent"));
                Files.write(destination, entry.getValue().content());
                checksums.put(entry.getKey(), entry.getValue().stamp().checksum());
            }
            Path staged = stagingRoot.resolve(descriptor.name() + "-" + UUID.randomUUID()).normalize();
            Files.move(temporary, staged, StandardCopyOption.ATOMIC_MOVE);
            return new InstalledPluginCandidate(new PluginId(descriptor.name()), descriptor, staged, checksums);
        } catch (RuntimeException exception) {
            deleteTree(temporary);
            throw discovery("descriptor_invalid", canonicalRoot.resolve(DESCRIPTOR_FILE),
                    "plugin descriptor is invalid", exception);
        } catch (IOException exception) {
            deleteTree(temporary);
            throw exception;
        }
    }

    private static Map<String, FileSnapshot> inventory(Path root) throws IOException {
        Map<String, FileSnapshot> inventory = new java.util.TreeMap<>();
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted().toList()) {
                if (path.equals(root)) {
                    continue;
                }
                if (Files.isSymbolicLink(path)) {
                    throw discovery("symbolic_link_rejected", path, "symbolic links are not allowed", null);
                }
                BasicFileAttributes attributes = Files.readAttributes(
                        path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isRegularFile()) {
                    String relative = root.relativize(path).toString()
                            .replace(path.getFileSystem().getSeparator(), "/");
                    byte[] content = Files.readAllBytes(path);
                    inventory.put(relative, new FileSnapshot(
                            new FileStamp(attributes.size(), attributes.lastModifiedTime().toMillis(),
                                    checksum(content)), content));
                } else if (!attributes.isDirectory()) {
                    throw discovery("unsupported_file_type", path, "plugin contains an unsupported file type", null);
                }
            }
        }
        return Map.copyOf(inventory);
    }

    private static Map<String, FileStamp> stamps(Map<String, FileSnapshot> inventory) {
        Map<String, FileStamp> stamps = new java.util.TreeMap<>();
        inventory.forEach((name, snapshot) -> stamps.put(name, snapshot.stamp()));
        return stamps;
    }

    private void waitForStability(Path path) {
        if (stabilityWindow.isZero()) {
            return;
        }
        try {
            Thread.sleep(stabilityWindow);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw discovery("stability_check_interrupted", path, "plugin stability check was interrupted", exception);
        }
    }

    private static List<Path> list(Path root) throws IOException {
        List<Path> entries = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(root)) {
            stream.forEach(entries::add);
        }
        entries.sort(Comparator.comparing(path -> path.getFileName().toString()));
        return entries;
    }

    private static void rejectLink(Path path) {
        if (Files.isSymbolicLink(path)) {
            throw discovery("symbolic_link_rejected", path, "symbolic links are not allowed", null);
        }
    }

    private static String checksum(byte[] content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(content);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static PluginDiscoveryError discovery(String code, Path path, String message, Throwable cause) {
        return new PluginDiscoveryError(code, path, message, cause);
    }

    private static void deleteTree(Path root) {
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ignored) {
            // Preserve the discovery failure; the temporary path remains isolated from admitted candidates.
        }
    }

    private record FileStamp(long size, long modifiedMillis, String checksum) {
    }

    private record FileSnapshot(FileStamp stamp, byte[] content) {
        private FileSnapshot {
            content = content.clone();
        }

        @Override
        public byte[] content() {
            return content.clone();
        }
    }
}
