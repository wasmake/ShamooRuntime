package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured load hook failure. */
public final class PluginLoadError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginLoadError(PluginId pluginId, UUID correlationId, Throwable cause) {
        super(pluginId, PluginLifecycleState.LOAD_FAILED, correlationId,
                "plugin_load_failed", "plugin " + pluginId + " failed to load", cause);
    }
}
