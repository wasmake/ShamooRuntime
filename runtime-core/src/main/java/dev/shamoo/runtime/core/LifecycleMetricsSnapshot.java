package dev.shamoo.runtime.core;

/** Measured lifecycle coordination counters. */
public record LifecycleMetricsSnapshot(
        long operations,
        long successfulHooks,
        long failedHooks,
        long timedOutHooks,
        long quarantines) {
}
