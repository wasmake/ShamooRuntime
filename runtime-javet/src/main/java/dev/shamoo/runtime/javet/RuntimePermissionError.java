package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;

/** Runtime policy denied an operation at a controlled boundary. */
@SuppressWarnings({"serial", "PMD.AvoidFieldNameMatchingMethodName"})
public final class RuntimePermissionError extends RuntimeError {
    private final String operation;
    private final String target;

    public RuntimePermissionError(PluginId pluginId, String operation, String target, String message) {
        super(pluginId, message, null, null, null);
        this.operation = operation;
        this.target = target;
    }

    public String operation() {
        return operation;
    }

    public String target() {
        return target;
    }
}
