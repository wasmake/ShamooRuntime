package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured disable hook failure. */
public final class PluginDisableError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginDisableError(PluginId pluginId, UUID correlationId, Throwable cause) {
        super(pluginId, PluginLifecycleState.DISABLE_FAILED, correlationId,
                "plugin_disable_failed", "plugin " + pluginId + " failed to disable", cause);
    }
}
