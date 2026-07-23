package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.CompatibilityResult;
import java.util.Objects;

/** Indicates that a valid manifest cannot run in the current runtime environment. */
public final class IncompatibleManifestException extends IllegalArgumentException {
    private static final long serialVersionUID = 1L;
    private final CompatibilityResult compatibilityResult;

    public IncompatibleManifestException(String pluginId, CompatibilityResult result) {
        super("Plugin " + Objects.requireNonNull(pluginId, "pluginId") + " is incompatible: "
                + Objects.requireNonNull(result, "result").reasons());
        this.compatibilityResult = result;
    }

    public CompatibilityResult result() {
        return compatibilityResult;
    }
}
