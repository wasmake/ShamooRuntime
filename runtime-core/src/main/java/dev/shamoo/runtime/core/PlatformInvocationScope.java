package dev.shamoo.runtime.core;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/** Carries the invoking script generation through one synchronous platform capability dispatch. */
public final class PlatformInvocationScope {
    private static final ThreadLocal<UUID> ACTIVE_GENERATION = new ThreadLocal<>();

    private PlatformInvocationScope() {
    }

    public static Optional<UUID> generation() {
        return Optional.ofNullable(ACTIVE_GENERATION.get());
    }

    public static <T> T invoke(UUID generation, Operation<T> operation) throws Exception {
        Objects.requireNonNull(generation, "generation");
        Objects.requireNonNull(operation, "operation");
        if (ACTIVE_GENERATION.get() != null) {
            throw new IllegalStateException("platform invocation scope is already active");
        }
        ACTIVE_GENERATION.set(generation);
        try {
            return operation.invoke();
        } finally {
            ACTIVE_GENERATION.remove();
        }
    }

    @FunctionalInterface
    public interface Operation<T> {
        T invoke() throws Exception;
    }
}
