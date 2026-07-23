package dev.shamoo.runtime.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable audit entry for an atomic lifecycle state transition. */
public record LifecycleTransition(
        PluginLifecycleState from,
        PluginLifecycleState to,
        Instant occurredAt,
        UUID correlationId,
        String reason) {
    public LifecycleTransition {
        Objects.requireNonNull(from, "from");
        Objects.requireNonNull(to, "to");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(correlationId, "correlationId");
        reason = Objects.requireNonNull(reason, "reason");
    }
}
