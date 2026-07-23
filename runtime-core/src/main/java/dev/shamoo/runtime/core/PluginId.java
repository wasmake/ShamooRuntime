package dev.shamoo.runtime.core;

import java.util.Objects;
import java.util.regex.Pattern;

/** Validated stable identity of a script plugin. */
public record PluginId(String value) {
    private static final Pattern VALID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");

    public PluginId {
        Objects.requireNonNull(value, "value");
        if (value.length() > 64 || !VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("invalid plugin id: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
