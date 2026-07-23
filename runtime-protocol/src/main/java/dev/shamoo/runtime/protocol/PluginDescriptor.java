package dev.shamoo.runtime.protocol;

import java.util.Objects;

/** Canonical immutable Shamoo plugin manifest version 1. */
public record PluginDescriptor(
        String name,
        String displayName,
        SemanticVersion version,
        ShamooRequirements shamoo,
        PlatformTargets platforms,
        DependencyPolicy dependencies,
        NodePolicy node,
        ReloadPolicy reload) {
    public PluginDescriptor {
        name = ManifestValidation.pluginId(name, "/name");
        displayName = ManifestValidation.text(displayName, "/displayName");
        version = Objects.requireNonNull(version, "version");
        shamoo = Objects.requireNonNull(shamoo, "shamoo");
        platforms = Objects.requireNonNull(platforms, "platforms");
        dependencies = Objects.requireNonNull(dependencies, "dependencies");
        node = Objects.requireNonNull(node, "node");
        reload = Objects.requireNonNull(reload, "reload");
    }
}
