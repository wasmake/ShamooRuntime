package dev.shamoo.runtime.codegen;

import dev.shamoo.runtime.protocol.ProtocolVersion;
import java.util.Objects;

/** Metadata emitted beside generated bindings and checked before registration. */
public record GeneratedBinding(String namespace, String typeName, ProtocolVersion protocolVersion) {
    public GeneratedBinding {
        namespace = requireIdentifier(namespace, "namespace");
        typeName = requireIdentifier(typeName, "typeName");
        protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
    }

    public void requireCompatible(ProtocolVersion runtimeVersion) {
        if (!runtimeVersion.isCompatibleWith(protocolVersion)) {
            throw new IllegalArgumentException(
                "binding protocol " + protocolVersion + " is incompatible with runtime " + runtimeVersion);
        }
    }

    private static String requireIdentifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$.-]*")) {
            throw new IllegalArgumentException(name + " is not a valid binding identifier");
        }
        return value;
    }
}
