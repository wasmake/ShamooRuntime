package dev.shamoo.runtime.protocol;

import java.io.Serializable;

/** An actionable reason that runtime admission failed. */
public record IncompatibilityReason(String code, String message) implements Serializable {
    private static final long serialVersionUID = 1L;

    public IncompatibilityReason {
        code = ManifestValidation.text(code, "code");
        message = ManifestValidation.text(message, "message");
    }
}
