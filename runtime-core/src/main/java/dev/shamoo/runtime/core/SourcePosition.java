package dev.shamoo.runtime.core;

import java.util.Objects;

/** One-based source location. */
public record SourcePosition(String resourceName, int line, int column) {
    public SourcePosition {
        Objects.requireNonNull(resourceName, "resourceName");
        if (line < 1 || column < 1) {
            throw new IllegalArgumentException("source positions are one-based");
        }
    }
}
