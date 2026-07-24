package dev.shamoo.runtime.bootstrap.velocity;

import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.plugin.PluginContainer;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.javet.HostedPluginStatus;
import dev.shamoo.runtime.platform.velocity.VelocityCommandBridge;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Permanent Velocity commands owned by the runtime bootstrap. */
final class VelocityRuntimeCommands {
    private static final PluginId RUNTIME_OWNER = new PluginId("shamooruntime");

    private VelocityRuntimeCommands() {
    }

    static void register(ProxyServer server, Object plugin, VelocityCommandBridge bridge,
            Supplier<List<HostedPluginStatus>> statuses, System.Logger logger) {
        register(server, plugin, bridge, "plugins", invocation -> {
            invocation.source().sendMessage(velocityPlugins(server.getPluginManager().getPlugins().stream().toList()));
            invocation.source().sendMessage(shamooPlugins(statuses.get()));
        }, logger);
        register(server, plugin, bridge, "ping",
                invocation -> invocation.source().sendMessage(Component.text("pong")), logger);
    }

    private static void register(ProxyServer server, Object plugin, VelocityCommandBridge bridge, String name,
            VelocityCommandBridge.SimpleDispatcher dispatcher, System.Logger logger) {
        CommandManager commands = server.getCommandManager();
        if (commands.hasCommand(name)) {
            logger.log(System.Logger.Level.WARNING, "Command /" + name + " is already registered; leaving it intact");
            return;
        }
        try {
            var metadata = commands.metaBuilder(name).plugin(plugin).build();
            bridge.registerSimple(RUNTIME_OWNER, metadata, dispatcher);
        } catch (IllegalArgumentException exception) {
            logger.log(System.Logger.Level.WARNING,
                    "Command /" + name + " was registered concurrently; leaving it intact", exception);
        }
    }

    static Component velocityPlugins(List<PluginContainer> plugins) {
        TextComponent.Builder message = Component.text()
                .append(Component.text("Velocity Plugins (" + plugins.size() + "): ", NamedTextColor.YELLOW));
        if (plugins.isEmpty()) {
            return message.append(Component.text("None", NamedTextColor.GRAY)).build();
        }
        for (int index = 0; index < plugins.size(); index++) {
            if (index > 0) {
                message.append(Component.text(", ", NamedTextColor.GRAY));
            }
            message.append(Component.text(plugins.get(index).getDescription().getId(), NamedTextColor.GRAY));
        }
        return message.build();
    }

    static Component shamooPlugins(List<HostedPluginStatus> statuses) {
        Objects.requireNonNull(statuses, "statuses");
        List<HostedPluginStatus> ordered = new ArrayList<>(statuses);
        ordered.sort(Comparator.comparing((HostedPluginStatus status) -> !status.active())
                .thenComparing(status -> status.pluginId().value()));
        TextComponent.Builder message = Component.text()
                .append(Component.text("Shamoo Plugins (" + ordered.size() + "): ", NamedTextColor.YELLOW));
        if (ordered.isEmpty()) {
            return message.append(Component.text("None", NamedTextColor.GRAY)).build();
        }
        for (int index = 0; index < ordered.size(); index++) {
            if (index > 0) {
                message.append(Component.text(", ", NamedTextColor.GRAY));
            }
            HostedPluginStatus status = ordered.get(index);
            message.append(Component.text(status.pluginId().value(), status.active()
                    ? NamedTextColor.GREEN : NamedTextColor.GRAY));
        }
        return message.build();
    }
}
