package dev.shamoo.runtime.bootstrap.paper;

import dev.shamoo.runtime.javet.HostedPluginStatus;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.function.Supplier;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.plugin.java.JavaPlugin;

/** Permanent Paper commands owned by the runtime bootstrap. */
final class PaperRuntimeCommands {
    private PaperRuntimeCommands() {
    }

    static void register(JavaPlugin plugin, Supplier<List<HostedPluginStatus>> statuses) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(statuses, "statuses");
        plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
            event.registrar().register("plugins", "Lists Java and Shamoo plugins", List.of(), new BasicCommand() {
                @Override
                public void execute(CommandSourceStack source, String[] arguments) {
                    plugin.getServer().dispatchCommand(source.getSender(), "bukkit:plugins");
                    source.getSender().sendMessage(shamooPlugins(statuses.get()));
                }

                @Override
                public String permission() {
                    return "bukkit.command.plugins";
                }
            });
            event.registrar().register("ping", "Replies with pong", List.of(),
                    (source, arguments) -> source.getSender().sendPlainMessage("pong"));
        });
    }

    static Component shamooPlugins(List<HostedPluginStatus> statuses) {
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
