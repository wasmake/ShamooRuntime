package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.SourcePosition;

/** Base structured failure from a per-plugin Node runtime. */
@SuppressWarnings({"serial", "PMD.AvoidFieldNameMatchingMethodName"})
public class RuntimeError extends RuntimeException {
    private final PluginId pluginId;
    private final SourcePosition sourcePosition;
    private final String scriptStack;

    public RuntimeError(
            PluginId pluginId,
            String message,
            SourcePosition sourcePosition,
            String scriptStack,
            Throwable cause) {
        super(message, cause);
        this.pluginId = pluginId;
        this.sourcePosition = sourcePosition;
        this.scriptStack = scriptStack;
    }

    public PluginId pluginId() {
        return pluginId;
    }

    public SourcePosition sourcePosition() {
        return sourcePosition;
    }

    public String scriptStack() {
        return scriptStack;
    }
}
