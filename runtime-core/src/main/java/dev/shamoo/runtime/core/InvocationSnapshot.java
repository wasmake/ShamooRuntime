package dev.shamoo.runtime.core;

/** Immutable invocation admission counters. */
public record InvocationSnapshot(
        boolean accepting,
        int active,
        long admitted,
        long completed,
        long rejected) {
}
