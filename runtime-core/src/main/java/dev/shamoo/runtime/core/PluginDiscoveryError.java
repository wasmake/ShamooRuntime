package dev.shamoo.runtime.core;

import java.nio.file.Path;
import java.util.Objects;

/** Structured rejection produced while inventorying the runtime plugins directory. */
public final class PluginDiscoveryError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String errorCode;
    private final String rejectedPath;

    public PluginDiscoveryError(String code, Path path, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = Objects.requireNonNull(code, "code");
        this.rejectedPath = Objects.requireNonNull(path, "path").toAbsolutePath().normalize().toString();
    }

    public String code() {
        return errorCode;
    }

    public Path path() {
        return Path.of(rejectedPath);
    }
}
