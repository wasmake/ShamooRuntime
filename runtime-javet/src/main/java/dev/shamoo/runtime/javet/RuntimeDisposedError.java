package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;

/** Submission attempted after shutdown began. */
@SuppressWarnings("serial")
public final class RuntimeDisposedError extends RuntimeError {
    public RuntimeDisposedError(PluginId pluginId) {
        super(pluginId, "runtime is not accepting work", null, null, null);
    }
}
