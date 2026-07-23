package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured lifecycle rejection caused by dependency state or compatibility. */
public final class PluginDependencyError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginDependencyError(
            PluginId pluginId,
            PluginLifecycleState phase,
            UUID correlationId,
            String code,
            String message) {
        super(pluginId, phase, correlationId, code, message, null);
    }
}
