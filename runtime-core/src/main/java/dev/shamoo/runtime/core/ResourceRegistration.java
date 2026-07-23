package dev.shamoo.runtime.core;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** Immutable view of a live plugin-owned resource. */
public record ResourceRegistration(
        UUID registrationId,
        PluginId owner,
        ResourceCategory category,
        String description,
        Instant registeredAt) {
    public ResourceRegistration {
        Objects.requireNonNull(registrationId, "registrationId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(category, "category");
        description = Objects.requireNonNull(description, "description");
        Objects.requireNonNull(registeredAt, "registeredAt");
    }
}
