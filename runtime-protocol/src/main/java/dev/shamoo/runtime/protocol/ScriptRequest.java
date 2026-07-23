package dev.shamoo.runtime.protocol;

import java.util.Map;
import java.util.Objects;

/** An immutable request to evaluate one script resource. */
public record ScriptRequest(String requestId, String source, Map<String, String> attributes) {
    public ScriptRequest {
        requestId = requireText(requestId, "requestId");
        source = Objects.requireNonNull(source, "source");
        attributes = Map.copyOf(Objects.requireNonNull(attributes, "attributes"));
    }

    private static String requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
