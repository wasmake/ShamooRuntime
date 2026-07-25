package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.ProtocolVersion;
import java.util.Map;
import java.util.Objects;

/** Generated binding identity required before a platform operation can be invoked. */
public record CompiledBindingMetadata(
        String namespace,
        String typeName,
        String componentId,
        String method,
        ProtocolVersion protocolVersion) {
    public CompiledBindingMetadata {
        namespace = identifier(namespace, "namespace");
        typeName = identifier(typeName, "typeName");
        componentId = metadataText(componentId, "componentId");
        method = metadataText(method, "method");
        Objects.requireNonNull(protocolVersion, "protocolVersion");
        if (!ProtocolVersion.CURRENT.isCompatibleWith(protocolVersion)) {
            throw new IllegalArgumentException("compiled binding protocol is incompatible with this runtime");
        }
    }

    public static CompiledBindingMetadata from(Map<?, ?> value) {
        Objects.requireNonNull(value, "metadata");
        return new CompiledBindingMetadata(text(value, "namespace"), text(value, "typeName"),
                text(value, "componentId"), text(value, "method"),
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
        long integer = number.longValue();
        if (integer < Integer.MIN_VALUE || integer > Integer.MAX_VALUE || number.doubleValue() != integer) {
            throw new IllegalArgumentException("compiled binding metadata requires integer " + key);
        }
        return (int) integer;
    }

    private static String identifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$.-]*")) {
            throw new IllegalArgumentException(name + " is not a generated binding identifier");
        }
        return value;
    }

    private static String metadataText(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " is not valid compiler metadata text");
        }
        return value;
    }
}
