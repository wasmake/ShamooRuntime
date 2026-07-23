package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured drain hook or active-invocation wait failure. */
public final class PluginDrainError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginDrainError(PluginId pluginId, UUID correlationId, Throwable cause) {
        super(pluginId, PluginLifecycleState.DRAIN_FAILED, correlationId,
                "plugin_drain_failed", "plugin " + pluginId + " failed to drain", cause);
    }
}
