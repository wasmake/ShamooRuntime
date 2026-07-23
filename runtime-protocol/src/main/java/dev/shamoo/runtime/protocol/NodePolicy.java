package dev.shamoo.runtime.protocol;

import java.util.List;
import java.util.Objects;

/** Explicit Node capabilities requested by plugin code. */
public record NodePolicy(
        List<String> builtins,
        FilesystemPolicy filesystem,
        boolean network,
        boolean workers,
        boolean childProcess,
        boolean nativeAddons) {
    public NodePolicy {
        builtins = ManifestValidation.builtins(builtins, "/node/builtins");
        filesystem = Objects.requireNonNull(filesystem, "filesystem");
    }

    @Override
    public List<String> builtins() {
        return List.copyOf(builtins);
    }
}
