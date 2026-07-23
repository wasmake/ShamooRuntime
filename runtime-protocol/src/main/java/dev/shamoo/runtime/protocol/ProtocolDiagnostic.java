package dev.shamoo.runtime.protocol;

import java.util.Objects;
import java.io.Serializable;

/** A stable, machine-readable protocol failure with a human-readable explanation. */
public record ProtocolDiagnostic(String code, String path, String message) implements Serializable {
    private static final long serialVersionUID = 1L;

    public ProtocolDiagnostic {
        code = requireText(code, "code");
        path = Objects.requireNonNull(path, "path");
        message = requireText(message, "message");
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
