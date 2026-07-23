package dev.shamoo.runtime.core;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable dependency graph and deterministic lifecycle ordering. */
public record DependencyResolution(
        List<PluginId> enableOrder,
        List<PluginId> disableOrder,
        Map<PluginId, Set<PluginId>> dependencies,
        Map<PluginId, List<DependencyBlock>> blocked,
        List<List<PluginId>> cycles) {
    public DependencyResolution {
        enableOrder = List.copyOf(enableOrder);
        disableOrder = List.copyOf(disableOrder);
        dependencies = dependencies.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
        blocked = blocked.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
        cycles = cycles.stream().map(List::copyOf).toList();
    }

    @Override
    public Map<PluginId, Set<PluginId>> dependencies() {
        return dependencies.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    @Override
    public Map<PluginId, List<DependencyBlock>> blocked() {
        return blocked.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
    }

    @Override
    public List<List<PluginId>> cycles() {
        return cycles.stream().map(List::copyOf).toList();
    }
}
