package dev.shamoo.runtime.core;

import java.time.Duration;
import java.util.UUID;

/** Structured timeout from a lifecycle hook or invocation drain. */
public final class LifecycleTimeoutError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public LifecycleTimeoutError(
            PluginId pluginId,
            PluginLifecycleState phase,
            UUID correlationId,
            Duration timeout,
            Throwable cause) {
        super(pluginId, phase, correlationId, "lifecycle_timeout",
                "plugin " + pluginId + " timed out during " + phase + " after " + timeout, cause);
    }
}
