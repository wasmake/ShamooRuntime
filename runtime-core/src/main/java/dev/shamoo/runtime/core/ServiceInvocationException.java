package dev.shamoo.runtime.core;

import java.util.UUID;

/** Correlated data-boundary failure for one cross-runtime service operation. */
public final class ServiceInvocationException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final UUID traceId;

    public ServiceInvocationException(String service, String operation, UUID correlationId, Throwable cause) {
        super("service " + service + "." + operation + " failed [correlation=" + correlationId + "]", cause);
        traceId = correlationId;
    }

    public UUID correlationId() {
        return traceId;
    }
}
