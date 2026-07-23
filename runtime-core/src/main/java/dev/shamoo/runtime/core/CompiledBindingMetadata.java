package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.ProtocolVersion;
import java.util.Map;
import java.util.Objects;

/** Generated binding identity required before a platform operation can be invoked. */
public record CompiledBindingMetadata(String namespace, String typeName, ProtocolVersion protocolVersion) {
    public CompiledBindingMetadata {
        namespace = identifier(namespace, "namespace");
        typeName = identifier(typeName, "typeName");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        if (!ProtocolVersion.CURRENT.isCompatibleWith(protocolVersion)) {
            throw new IllegalArgumentException("compiled binding protocol is incompatible with this runtime");
        }
    }

    public static CompiledBindingMetadata from(Map<?, ?> value) {
        Objects.requireNonNull(value, "metadata");
        return new CompiledBindingMetadata(text(value, "namespace"), text(value, "typeName"),
                new ProtocolVersion(number(value, "protocolMajor"), number(value, "protocolMinor")));
    }

    private static String text(Map<?, ?> value, String key) {
        Object field = value.get(key);
        if (!(field instanceof String text)) {
            throw new IllegalArgumentException("compiled binding metadata requires " + key);
        }
        return text;
    }

    private static int number(Map<?, ?> value, String key) {
        Object field = value.get(key);
        if (!(field instanceof Number number)) {
            throw new IllegalArgumentException("compiled binding metadata requires " + key);
        }
        return number.intValue();
    }

    private static String identifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$.-]*")) {
            throw new IllegalArgumentException(name + " is not a generated binding identifier");
        }
        return value;
    }
}
