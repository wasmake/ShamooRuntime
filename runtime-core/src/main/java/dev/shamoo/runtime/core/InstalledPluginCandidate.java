package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.PluginDescriptor;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;

/** Immutable discovered plugin installation; it does not imply activation. */
public record InstalledPluginCandidate(
        PluginId pluginId,
        PluginDescriptor descriptor,
        Path root,
        Map<String, String> checksums) {
    public InstalledPluginCandidate {
        Objects.requireNonNull(pluginId, "pluginId");
        Objects.requireNonNull(descriptor, "descriptor");
        root = Objects.requireNonNull(root, "root").toAbsolutePath().normalize();
        checksums = Map.copyOf(checksums);
        if (!pluginId.value().equals(descriptor.name())) {
            throw new IllegalArgumentException("candidate identity does not match descriptor");
        }
        if (pluginId.value().equals(ResourceRegistry.RUNTIME_OWNER.value())) {
            throw new IllegalArgumentException("plugin id is reserved: " + pluginId);
        }
    }
}
