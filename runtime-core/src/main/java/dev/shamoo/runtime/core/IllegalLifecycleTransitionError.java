package dev.shamoo.runtime.core;

import java.util.UUID;

/** Raised when a caller requests a transition outside the central transition table. */
public final class IllegalLifecycleTransitionError extends LifecycleError {
    private static final long serialVersionUID = 1L;

    public IllegalLifecycleTransitionError(
            PluginId pluginId,
            PluginLifecycleState from,
            PluginLifecycleState to,
            UUID correlationId) {
        super(pluginId, from, correlationId, "illegal_transition",
                "illegal lifecycle transition from " + from + " to " + to, null);
    }
}
