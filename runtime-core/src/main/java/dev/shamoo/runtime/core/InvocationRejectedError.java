package dev.shamoo.runtime.core;

/** Raised when a plugin is no longer admitting invocations. */
public final class InvocationRejectedError extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public InvocationRejectedError(PluginId pluginId) {
        super("plugin " + pluginId + " is not accepting invocations");
    }
}
