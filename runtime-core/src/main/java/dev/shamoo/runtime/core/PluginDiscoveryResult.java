package dev.shamoo.runtime.core;

import java.util.List;

/** Complete discovery outcome; one bad installation does not hide valid siblings. */
public record PluginDiscoveryResult(
        List<InstalledPluginCandidate> candidates,
        List<PluginDiscoveryError> errors) {
    public PluginDiscoveryResult {
        candidates = List.copyOf(candidates);
        errors = List.copyOf(errors);
    }
}
