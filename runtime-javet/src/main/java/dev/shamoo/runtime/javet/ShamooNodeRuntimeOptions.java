package dev.shamoo.runtime.javet;

import java.time.Duration;
import java.util.Objects;

/** Operational limits for one Node isolate. */
public record ShamooNodeRuntimeOptions(
        int queueCapacity,
        Duration invocationTimeout,
        Duration closeTimeout) {
    private static final int MINIMUM_QUEUE_CAPACITY = 1;
    public static final ShamooNodeRuntimeOptions DEFAULT =
        new ShamooNodeRuntimeOptions(256, Duration.ofSeconds(30), Duration.ofSeconds(10));

    public ShamooNodeRuntimeOptions {
        if (queueCapacity < MINIMUM_QUEUE_CAPACITY) {
            throw new IllegalArgumentException("queueCapacity must be positive");
        }
        invocationTimeout = positive(invocationTimeout, "invocationTimeout");
        closeTimeout = positive(closeTimeout, "closeTimeout");
    }

    private static Duration positive(Duration duration, String name) {
        Objects.requireNonNull(duration, name);
        if (duration.compareTo(Duration.ofMillis(1)) < 0) {
            throw new IllegalArgumentException(name + " must be at least 1 millisecond");
        }
        return duration;
    }
}
