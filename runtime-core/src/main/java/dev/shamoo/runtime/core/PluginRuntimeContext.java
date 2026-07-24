package dev.shamoo.runtime.core;

import java.util.Objects;
import java.util.UUID;

/** Core-owned services supplied to a runtime implementation. */
public record PluginRuntimeContext(
        InstalledPluginCandidate candidate,
        ResourceRegistry resources,
        InvocationController invocations,
        PlatformCapabilities platformCapabilities,
        PluginServices services,
        PluginEvents events,
        UUID generationId) {
    public PluginRuntimeContext {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(invocations, "invocations");
        Objects.requireNonNull(platformCapabilities, "platformCapabilities");
        Objects.requireNonNull(services, "services");
        Objects.requireNonNull(events, "events");
        Objects.requireNonNull(generationId, "generationId");
    }

    public PluginRuntimeContext(
            InstalledPluginCandidate candidate,
            ResourceRegistry resources,
            InvocationController invocations,
            PlatformCapabilities platformCapabilities,
            PluginServices services,
            PluginEvents events) {
        this(candidate, resources, invocations, platformCapabilities, services, events, UUID.randomUUID());
    }
}
