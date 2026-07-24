package dev.shamoo.runtime.core;

/** Raised by a stable service proxy when no active compatible provider can admit the call. */
public final class ServiceUnavailableException extends IllegalStateException {
    private static final long serialVersionUID = 1L;

    public ServiceUnavailableException(String serviceName) {
        super("no active compatible provider for service " + serviceName);
    }

    public ServiceUnavailableException(String serviceName, java.util.UUID correlationId) {
        super("no active compatible provider for service " + serviceName
                + " [correlation=" + correlationId + "]");
    }
}
