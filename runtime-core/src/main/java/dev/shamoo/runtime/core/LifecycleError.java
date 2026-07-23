package dev.shamoo.runtime.core;

import java.util.Objects;
import java.util.UUID;

/** Structured base error emitted by lifecycle coordination. */
public class LifecycleError extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final String pluginIdentity;
    private final PluginLifecycleState lifecyclePhase;
    private final UUID operationCorrelationId;
    private final String errorCode;

    public LifecycleError(
            PluginId pluginId,
            PluginLifecycleState phase,
            UUID correlationId,
            String code,
            String message,
            Throwable cause) {
        super(message, cause);
        this.pluginIdentity = Objects.requireNonNull(pluginId, "pluginId").value();
        this.lifecyclePhase = Objects.requireNonNull(phase, "phase");
        this.operationCorrelationId = Objects.requireNonNull(correlationId, "correlationId");
        this.errorCode = Objects.requireNonNull(code, "code");
    }

    public final PluginId pluginId() {
        return new PluginId(pluginIdentity);
    }

    public final PluginLifecycleState phase() {
        return lifecyclePhase;
    }

    public final UUID correlationId() {
        return operationCorrelationId;
    }

    public final String code() {
        return errorCode;
    }
}
