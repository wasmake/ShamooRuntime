package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.SourcePosition;

/** Script compilation or execution failure. */
@SuppressWarnings("serial")
public final class RuntimeEvaluationError extends RuntimeError {
    public RuntimeEvaluationError(
            PluginId pluginId,
            String message,
            SourcePosition sourcePosition,
            String scriptStack,
            Throwable cause) {
        super(pluginId, message, sourcePosition, scriptStack, cause);
    }
}
