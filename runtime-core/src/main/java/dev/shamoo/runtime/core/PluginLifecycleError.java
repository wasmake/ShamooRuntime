package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured hook failure with a phase-specific code. */
public final class PluginLifecycleError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public PluginLifecycleError(
            PluginId pluginId,
            PluginLifecycleState phase,
            UUID correlationId,
            String code,
            Throwable cause) {
        super(pluginId, phase, correlationId, code,
                "plugin " + pluginId + " failed during " + phase, cause);
    }
}
