package dev.shamoo.runtime.core;

import java.util.UUID;

/** Structured unload failure caused by plugin-owned resources. */
public final class ResourceCleanupError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public ResourceCleanupError(PluginId pluginId, UUID correlationId, Throwable cause) {
        super(pluginId, PluginLifecycleState.UNLOAD_FAILED, correlationId,
                "resource_cleanup_failed", "plugin " + pluginId + " resource cleanup failed", cause);
    }
}
