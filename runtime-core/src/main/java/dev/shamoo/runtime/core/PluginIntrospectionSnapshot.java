package dev.shamoo.runtime.core;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/** One immutable administrative view assembled under coordinator serialization. */
public record PluginIntrospectionSnapshot(
        PluginId pluginId,
        PluginLifecycleState state,
        List<LifecycleTransition> transitions,
        Set<PluginId> dependencies,
        List<DependencyBlock> blockedReasons,
        List<ResourceRegistration> resources,
        InvocationSnapshot invocations,
        LifecycleMetricsSnapshot metrics,
        Instant capturedAt) {
    public PluginIntrospectionSnapshot {
        transitions = List.copyOf(transitions);
        dependencies = Set.copyOf(dependencies);
        blockedReasons = List.copyOf(blockedReasons);
        resources = List.copyOf(resources);
    }
}
