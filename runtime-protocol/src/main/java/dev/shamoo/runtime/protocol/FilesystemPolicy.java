package dev.shamoo.runtime.protocol;

import java.util.List;

/** Plugin-root-relative filesystem access allowlists. */
public record FilesystemPolicy(List<String> read, List<String> write) {
    public FilesystemPolicy {
        read = ManifestValidation.paths(read, "/node/filesystem/read");
        write = ManifestValidation.paths(write, "/node/filesystem/write");
    }

    @Override
    public List<String> read() {
        return List.copyOf(read);
    }

    @Override
    public List<String> write() {
        return List.copyOf(write);
    }
}
