package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper lifecycle/Brigadier command adapter with an explicit legacy fallback capability. */
public final class PaperCommandBridge {
    private final JavaPlugin plugin;
    private final ResourceRegistry resources;
    private final Capability selectedCapability;
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();
    private final AtomicReference<Commands> lifecycleRegistrar = new AtomicReference<>();

    public PaperCommandBridge(JavaPlugin plugin, ResourceRegistry resources) {
        this(plugin, resources, selectCapability(true));
    }

    public PaperCommandBridge(JavaPlugin plugin, ResourceRegistry resources, Capability capability) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.selectedCapability = Objects.requireNonNull(capability, "capability");
        if (capability == Capability.LIFECYCLE_BRIGADIER) {
            plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                lifecycleRegistrar.set(event.registrar());
                registrations.forEach(registration -> registration.register(event.registrar()));
            });
        }
    }

    public Registration register(PluginId owner, String name, List<String> aliases, CommandDispatcher dispatcher) {
        Registration registration = selectedCapability == Capability.LIFECYCLE_BRIGADIER
                ? Registration.lifecycle(name, aliases, dispatcher, registrations)
                : Registration.commandMap(plugin, name, aliases, dispatcher);
        registrations.add(registration);
        Commands registrar = lifecycleRegistrar.get();
        if (registrar != null && selectedCapability == Capability.LIFECYCLE_BRIGADIER) {
            registration.register(registrar);
        }
        return resources.register(owner, ResourceCategory.COMMAND, name, registration);
    }

    public Capability capability() {
        return selectedCapability;
    }

    public static Capability selectCapability(boolean lifecycleApiAvailable) {
        // The lifecycle registrar in the pinned API has no immediate unregister operation.
        return Capability.COMMAND_MAP_FALLBACK;
    }

    static void removeKnownCommands(java.util.Map<String, Command> commands, Command command) {
        commands.entrySet().removeIf(entry -> entry.getValue().equals(command));
    }

    @FunctionalInterface
    public interface CommandDispatcher {
        boolean execute(CommandSender sender, String alias, List<String> arguments);
    }

    public enum Capability { LIFECYCLE_BRIGADIER, COMMAND_MAP_FALLBACK }

    public static final class Registration implements AutoCloseable {
        private final String name;
        private final List<String> aliases;
        private final AtomicReference<CommandDispatcher> dispatcher;
        private final List<Registration> registrations;
        private final CommandMap map;
        private final Command command;

        private Registration(String name, List<String> aliases, CommandDispatcher dispatcher,
                List<Registration> registrations, CommandMap commandMap, Command command) {
            this.name = Objects.requireNonNull(name, "name");
            this.aliases = List.copyOf(aliases);
            this.dispatcher = new AtomicReference<>(Objects.requireNonNull(dispatcher, "dispatcher"));
            this.registrations = registrations;
            this.map = commandMap;
            this.command = command;
        }

        private static Registration lifecycle(String name, List<String> aliases, CommandDispatcher dispatcher,
                List<Registration> registrations) {
            return new Registration(name, aliases, dispatcher, registrations, null, null);
        }

        private static Registration commandMap(JavaPlugin plugin, String name, List<String> aliases,
                CommandDispatcher dispatcher) {
            CommandMap map = plugin.getServer().getCommandMap();
            AtomicReference<Registration> reference = new AtomicReference<>();
            Command command = new Command(name, "", "/" + name, List.copyOf(aliases)) {
                @Override
                public boolean execute(CommandSender sender, String alias, String[] arguments) {
                    return reference.get().dispatch(sender, alias, List.of(arguments));
                }
            };
            Registration registration = new Registration(name, aliases, dispatcher, null, map, command);
            reference.set(registration);
            if (!map.register(plugin.getName().toLowerCase(Locale.ROOT), command)) {
                throw new IllegalStateException("command label is already registered: " + name);
            }
            return registration;
        }

        private void register(Commands registrar) {
            if (dispatcher.get() == null) {
                return;
            }
            BasicCommand target = new BasicCommand() {
                @Override
                public void execute(CommandSourceStack source, String[] arguments) {
                    dispatch(source.getSender(), name, List.of(arguments));
                }
            };
            registrar.register(name, "Shamoo runtime command", aliases, target);
        }

        public void replaceDispatcher(CommandDispatcher replacement) {
            dispatcher.set(Objects.requireNonNull(replacement, "replacement"));
        }

        private boolean dispatch(CommandSender sender, String alias, List<String> arguments) {
            CommandDispatcher current = dispatcher.get();
            return current != null && current.execute(sender, alias, arguments);
        }

        @Override
        public void close() {
            dispatcher.set(null);
            if (registrations != null) {
                registrations.remove(this);
            }
            if (command != null) {
                command.unregister(map);
                removeKnownCommands(map.getKnownCommands(), command);
            }
        }

    }
}
