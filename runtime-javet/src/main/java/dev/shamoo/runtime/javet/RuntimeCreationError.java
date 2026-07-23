package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;

/** Native Node isolate creation failure. */
@SuppressWarnings("serial")
public final class RuntimeCreationError extends RuntimeError {
    public RuntimeCreationError(PluginId pluginId, String message, Throwable cause) {
        super(pluginId, message, null, null, cause);
    }
}
