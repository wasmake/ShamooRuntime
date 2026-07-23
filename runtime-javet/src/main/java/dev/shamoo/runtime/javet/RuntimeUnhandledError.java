package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;

/** Unhandled promise rejection or uncaught asynchronous exception. */
@SuppressWarnings("serial")
public final class RuntimeUnhandledError extends RuntimeError {
    public RuntimeUnhandledError(PluginId pluginId, String message, String scriptStack, Throwable cause) {
        super(pluginId, message, null, scriptStack, cause);
    }
}
