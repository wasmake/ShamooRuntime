package dev.shamoo.runtime.protocol;

import java.util.List;

/** Indicates that manifest JSON cannot be decoded into a valid descriptor. */
public final class ManifestParseException extends RuntimeProtocolException {
    private static final long serialVersionUID = 1L;

    public ManifestParseException(ProtocolDiagnostic diagnostic, Throwable cause) {
        super(diagnostic.message(), List.of(diagnostic), cause);
    }
}
