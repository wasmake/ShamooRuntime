package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;

/** Bounded invocation queue rejected a submission. */
@SuppressWarnings("serial")
public final class RuntimeQueueFullError extends RuntimeError {
    public RuntimeQueueFullError(PluginId pluginId) {
        super(pluginId, "runtime invocation queue is full", null, null, null);
    }
}
