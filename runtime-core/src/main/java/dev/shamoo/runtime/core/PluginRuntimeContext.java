package dev.shamoo.runtime.core;

import java.util.Objects;

/** Core-owned services supplied to a runtime implementation. */
public record PluginRuntimeContext(
        InstalledPluginCandidate candidate,
        ResourceRegistry resources,
        InvocationController invocations) {
    public PluginRuntimeContext {
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(resources, "resources");
        Objects.requireNonNull(invocations, "invocations");
    }
}
