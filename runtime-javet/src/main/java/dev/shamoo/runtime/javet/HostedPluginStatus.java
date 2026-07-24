package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;
import java.util.Objects;

/** User-facing status for an installed Shamoo plugin, including candidates rejected before lifecycle admission. */
public record HostedPluginStatus(PluginId pluginId, boolean active) {
    public HostedPluginStatus {
        Objects.requireNonNull(pluginId, "pluginId");
    }
}
