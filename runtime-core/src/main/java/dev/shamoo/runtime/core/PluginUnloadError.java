package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured unload hook failure. */
public final class PluginUnloadError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginUnloadError(PluginId pluginId, UUID correlationId, Throwable cause) {
        super(pluginId, PluginLifecycleState.UNLOAD_FAILED, correlationId,
                "plugin_unload_failed", "plugin " + pluginId + " failed to unload", cause);
    }
}
