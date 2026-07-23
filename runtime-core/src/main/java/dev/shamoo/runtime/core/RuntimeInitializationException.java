package dev.shamoo.runtime.core;

/** Indicates that a runtime could not acquire its engine resources. */
public final class RuntimeInitializationException extends Exception {
    private static final long serialVersionUID = 1L;

    public RuntimeInitializationException(String message, Throwable cause) {
        super(message, cause);
    }
}
