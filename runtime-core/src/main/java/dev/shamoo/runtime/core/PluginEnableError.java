package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured enable hook failure. */
public final class PluginEnableError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginEnableError(PluginId pluginId, UUID correlationId, Throwable cause) {
        super(pluginId, PluginLifecycleState.ENABLE_FAILED, correlationId,
                "plugin_enable_failed", "plugin " + pluginId + " failed to enable", cause);
    }
}
