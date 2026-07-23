package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Locale;

/** Server platform names shared with TypeScript protocol v1. */
public enum PlatformKind {
    PAPER,
    VELOCITY;

    @JsonCreator
    public static PlatformKind fromJson(String value) {
        if (value == null) {
            ManifestValidation.fail("invalid_platform", "/platform", "platform must not be null");
        }
        try {
            return valueOf(value.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            ManifestValidation.fail("invalid_platform", "/platform", "unsupported platform: " + value);
            return PAPER;
        }
    }

    @JsonValue
    public String jsonValue() {
        return name().toLowerCase(Locale.ROOT);
    }
}
