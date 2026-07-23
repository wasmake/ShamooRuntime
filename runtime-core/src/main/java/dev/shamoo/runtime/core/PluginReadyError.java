package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured ready hook failure. */
public final class PluginReadyError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginReadyError(PluginId pluginId, UUID correlationId, Throwable cause) {
        super(pluginId, PluginLifecycleState.READY_FAILED, correlationId,
                "plugin_ready_failed", "plugin " + pluginId + " failed to become ready", cause);
    }
}
