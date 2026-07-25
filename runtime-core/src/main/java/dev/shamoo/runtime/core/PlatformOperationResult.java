package dev.shamoo.runtime.core;

import java.util.Objects;

/** A platform operation result with an optional generation-owned resource. */
public record PlatformOperationResult<T>(T value, AutoCloseable resource) {
    public PlatformOperationResult {
        Objects.requireNonNull(value, "value");
    }

    public static <T> PlatformOperationResult<T> value(T value) {
        return new PlatformOperationResult<>(value, null);
    }

    public static <T> PlatformOperationResult<T> owned(T value, AutoCloseable resource) {
        return new PlatformOperationResult<>(value, Objects.requireNonNull(resource, "resource"));
    }
}
