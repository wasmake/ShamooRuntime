package dev.shamoo.runtime.protocol;

import java.util.List;

/** Indicates a well-formed value that violates the manifest contract. */
public final class ManifestValidationException extends RuntimeProtocolException {
    private static final long serialVersionUID = 1L;

    public ManifestValidationException(ProtocolDiagnostic diagnostic) {
        super(diagnostic.message(), List.of(diagnostic));
    }
}
