package dev.shamoo.runtime.core;

/** Truly measured per-runtime counters. Active native handles are intentionally not inferred. */
public record RuntimeMetricsSnapshot(
        RuntimeState state,
        int activeInvocations,
        int queuedInvocations,
        long submittedInvocations,
        long completedInvocations,
        long rejectedInvocations,
        long unhandledErrors,
        int registeredResources,
        int registeredJavaCallbacks,
        int sourceMaps) {
}
