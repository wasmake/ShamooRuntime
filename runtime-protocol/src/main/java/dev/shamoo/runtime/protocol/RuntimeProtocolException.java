package dev.shamoo.runtime.protocol;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/** Base class for protocol failures that retain structured diagnostics. */
public abstract sealed class RuntimeProtocolException extends IllegalArgumentException
        permits ManifestParseException, ManifestSerializationException, ManifestValidationException {
    private static final long serialVersionUID = 1L;
    @SuppressWarnings("PMD.LooseCoupling")
    private final ArrayList<ProtocolDiagnostic> protocolDiagnostics;

    protected RuntimeProtocolException(String message, List<ProtocolDiagnostic> diagnostics) {
        super(message);
        this.protocolDiagnostics = new ArrayList<>(diagnostics);
    }

    protected RuntimeProtocolException(String message, List<ProtocolDiagnostic> diagnostics, Throwable cause) {
        super(message, cause);
        this.protocolDiagnostics = new ArrayList<>(diagnostics);
    }

    public final List<ProtocolDiagnostic> diagnostics() {
        return List.copyOf(Objects.requireNonNull(protocolDiagnostics, "protocolDiagnostics"));
    }
}
