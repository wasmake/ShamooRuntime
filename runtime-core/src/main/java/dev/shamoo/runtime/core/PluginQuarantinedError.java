package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured rejection of operations on a quarantined plugin. */
public final class PluginQuarantinedError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginQuarantinedError(PluginId pluginId, UUID correlationId) {
        super(pluginId, PluginLifecycleState.QUARANTINED, correlationId,
                "plugin_quarantined", "plugin " + pluginId + " is quarantined", null);
    }
}
