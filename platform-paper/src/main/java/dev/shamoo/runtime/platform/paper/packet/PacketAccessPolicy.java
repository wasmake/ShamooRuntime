package dev.shamoo.runtime.platform.paper.packet;

import dev.shamoo.runtime.core.PluginId;
import java.util.Objects;
import java.util.Set;

/** Immutable server-operator opt-in for exact-version NMS packet access. */
public record PacketAccessPolicy(boolean enabled, Set<PluginId> permittedPlugins) {
    public PacketAccessPolicy {
        permittedPlugins = Set.copyOf(Objects.requireNonNull(permittedPlugins, "permittedPlugins"));
    }

    public void require(PluginId pluginId) {
        if (!enabled || !permittedPlugins.contains(pluginId)) {
            throw new SecurityException("plugin is not permitted to use unstable Paper packet access: " + pluginId);
        }
    }
}
