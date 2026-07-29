package dev.shamoo.runtime.javet;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Stable policy-confined UTF-8 storage outside watched plugin artifact snapshots. */
@SuppressWarnings("PMD.PreserveStackTrace")
public final class PluginTextFileStore {
    private static final int MAXIMUM_TEXT_BYTES = 4 * 1024 * 1024;
    private final Set<String> readable;
    private final Path root;
    private final Set<String> writable;

    public PluginTextFileStore(Path root, Path defaults, List<String> readable, List<String> writable)
            throws IOException {
        this.readable = Set.copyOf(readable);
        this.writable = Set.copyOf(writable);
        Objects.requireNonNull(root, "root");
        if (Files.isSymbolicLink(root)) {
            throw new IOException("plugin data root must not be a symbolic link");
        }
        Files.createDirectories(root);
        if (Files.isSymbolicLink(root)) {
            throw new IOException("plugin data root must not be a symbolic link");
        }
        this.root = root.toRealPath(LinkOption.NOFOLLOW_LINKS);
        seed(Objects.requireNonNull(defaults, "defaults"));
    }

    public String read(String requested) {
        Path target = resolve(requested, readable, "read");
        try {
            byte[] bytes = Files.readAllBytes(target);
            if (bytes.length > MAXIMUM_TEXT_BYTES) {
                throw new IllegalArgumentException("plugin text file exceeds 4 MiB: " + requested);
            }
            return new String(bytes, StandardCharsets.UTF_8);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read plugin text file: " + requested, exception);
        }
    }

    public void write(String requested, String content) {
        Objects.requireNonNull(content, "content");
        byte[] bytes = content.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAXIMUM_TEXT_BYTES) {
            throw new IllegalArgumentException("plugin text file exceeds 4 MiB: " + requested);
        }
        Path target = resolve(requested, writable, "write");
        Path temporary = target.resolveSibling("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to write plugin text file: " + requested, exception);
        } finally {
            try {
                Files.deleteIfExists(temporary);
            } catch (IOException ignored) {
                // A later write uses a unique temporary name.
            }
        }
    }

    private Path resolve(String requested, Set<String> allowlist, String operation) {
        if (requested == null || requested.isBlank() || requested.indexOf('\0') >= 0 || requested.contains("\\")) {
            throw denied(operation, requested);
        }
        Path relative;
        try {
            relative = Path.of(requested).normalize();
        } catch (RuntimeException exception) {
            throw denied(operation, requested);
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw denied(operation, requested);
        }
        String normalized = relative.toString().replace('\\', '/');
        boolean allowed = allowlist.stream().anyMatch(entry -> {
            String rule = entry.startsWith("./") ? entry.substring(2) : entry;
            return rule.isEmpty() || normalized.equals(rule) || normalized.startsWith(rule + "/");
        });
        if (!allowed) {
            throw denied(operation, requested);
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root) || containsSymbolicLink(target.getParent())
                || Files.isSymbolicLink(target)) {
            throw denied(operation, requested);
        }
        return target;
    }

    private boolean containsSymbolicLink(Path directory) {
        if (directory == null) {
            return true;
        }
        Path current = root;
        for (Path component : root.relativize(directory)) {
            current = current.resolve(component);
            if (Files.isSymbolicLink(current) || !Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS)) {
                return true;
            }
        }
        return false;
    }

    private void seed(Path defaults) throws IOException {
        if (!Files.isDirectory(defaults, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Set<String> seeds = new java.util.LinkedHashSet<>(readable);
        seeds.addAll(writable);
        for (String configured : seeds) {
            String normalized = configured.startsWith("./") ? configured.substring(2) : configured;
            Path relative = normalized.isEmpty() ? Path.of("") : Path.of(normalized).normalize();
            if (relative.isAbsolute() || relative.startsWith("..")) {
                throw new IOException("plugin default path is not relative: " + configured);
            }
            copyDefaults(defaults.resolve(relative), root.resolve(relative));
        }
    }

    private void copyDefaults(Path sourceRoot, Path targetRoot) throws IOException {
        if (!Files.exists(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        if (Files.isRegularFile(sourceRoot, LinkOption.NOFOLLOW_LINKS)) {
            if (!targetRoot.normalize().startsWith(root) || containsSymbolicLink(targetRoot.getParent())
                    || Files.isSymbolicLink(targetRoot)) {
                throw new IOException("plugin data target must not contain symbolic links");
            }
            if (!Files.exists(targetRoot, LinkOption.NOFOLLOW_LINKS)) {
                Files.createDirectories(Objects.requireNonNull(targetRoot.getParent(), "target parent"));
                Files.copy(sourceRoot, targetRoot);
            }
            return;
        }
        try (var paths = Files.walk(sourceRoot)) {
            for (Path source : paths.sorted().toList()) {
                if (Files.isSymbolicLink(source)) {
                    throw new IOException("plugin defaults must not contain symbolic links");
                }
                Path relative = sourceRoot.relativize(source);
                Path target = targetRoot.resolve(relative);
                if (!target.normalize().startsWith(root) || containsSymbolicLink(target.getParent())
                        || Files.isSymbolicLink(target)) {
                    throw new IOException("plugin data target must not contain symbolic links");
                }
                if (Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
                    Files.createDirectories(target);
                } else if (Files.isRegularFile(source, LinkOption.NOFOLLOW_LINKS) && !Files.exists(target)) {
                    Files.copy(source, target);
                }
            }
        }
    }

    private static SecurityException denied(String operation, String path) {
        return new SecurityException("plugin file " + operation + " is not allowed: " + path);
    }
}
