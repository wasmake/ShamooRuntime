package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;

/** Module specifier was invalid, absent, or unsupported. */
@SuppressWarnings({"serial", "PMD.AvoidFieldNameMatchingMethodName"})
public final class RuntimeModuleResolutionError extends RuntimeError {
    private final String specifier;

    public RuntimeModuleResolutionError(PluginId pluginId, String specifier, String message, Throwable cause) {
        super(pluginId, message, null, null, cause);
        this.specifier = specifier;
    }

    public String specifier() {
        return specifier;
    }
}
