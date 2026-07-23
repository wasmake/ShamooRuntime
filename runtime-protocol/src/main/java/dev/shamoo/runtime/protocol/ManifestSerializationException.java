package dev.shamoo.runtime.protocol;

import java.util.List;

/** Indicates that a validated descriptor could not be encoded. */
public final class ManifestSerializationException extends RuntimeProtocolException {
    private static final long serialVersionUID = 1L;

    public ManifestSerializationException(ProtocolDiagnostic diagnostic, Throwable cause) {
        super(diagnostic.message(), List.of(diagnostic), cause);
    }
}
