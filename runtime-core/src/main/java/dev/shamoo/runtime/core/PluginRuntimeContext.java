package dev.shamoo.runtime.core;

import java.util.Objects;

/** Core-owned services supplied to a runtime implementation. */
public record PluginRuntimeContext(
        InstalledPluginCandidate candidate,
        ResourceRegistry resources,
        InvocationController invocations,
        PlatformCapabilities platformCapabilities) {
    public PluginRuntimeContext(InstalledPluginCandidate candidate, ResourceRegistry resources,
            InvocationController invocations) {
        this(candidate, resources, invocations, PlatformCapabilities.NONE);
    }

    public PluginRuntimeContext {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(invocations, "invocations");
        Objects.requireNonNull(platformCapabilities, "platformCapabilities");
    }
}
