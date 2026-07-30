package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.CompiledBindingMetadata;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ScriptCallback;
import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import io.papermc.paper.command.brigadier.Commands;
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;

/** Aggregates strict script routes behind owned Paper command registrations. */
@SuppressWarnings({"PMD.CloseResource", "PMD.AvoidFieldNameMatchingMethodName", "PMD.CompareObjectsWithEquals",
        "PMD.LooseCoupling"})
public final class PaperCommandBridge implements AutoCloseable {
    private final JavaPlugin plugin;
    private final PaperCommandContextBridge contexts;
    private final Capability selectedCapability;
    private final Map<CommandKey, Aggregate> aggregates = new HashMap<>();
    private final List<NativeRegistration> nativeRegistrations = new CopyOnWriteArrayList<>();
    private final AtomicReference<Commands> lifecycleRegistrar = new AtomicReference<>();
    private final AtomicLong routeSequence = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public PaperCommandBridge(JavaPlugin plugin, PaperCommandContextBridge contexts) {
        this(plugin, contexts, selectCapability(true));
    }

    PaperCommandBridge(JavaPlugin plugin, PaperCommandContextBridge contexts, Capability capability) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        selectedCapability = Objects.requireNonNull(capability, "capability");
        if (capability == Capability.LIFECYCLE_BRIGADIER) {
            plugin.getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, event -> {
                lifecycleRegistrar.set(event.registrar());
                nativeRegistrations.forEach(registration -> registration.register(event.registrar()));
            });
        }
    }

    public CompletionStage<RouteRegistration> register(PluginId owner, CompiledBindingMetadata metadata, String root,
            List<String> aliases, Object descriptor, ScriptCallback callback) {
        ScriptCallback ownedCallback = Objects.requireNonNull(callback, "callback");
        return contexts.scheduleGlobal(() -> registerOwned(
                owner, metadata, root, aliases, descriptor, ownedCallback)).whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        ownedCallback.close();
                    }
                });
    }

    private RouteRegistration registerOwned(PluginId owner, CompiledBindingMetadata metadata, String root,
            List<String> aliases, Object descriptor, ScriptCallback callback) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(metadata, "metadata");
        String commandRoot = commandLabel(root, "root");
        List<String> commandAliases = aliases(aliases);
        PaperCommandRoute route = PaperCommandRoute.parse(plugin.getServer(), descriptor);
        RouteTarget target = new RouteTarget(metadata.componentId(), metadata.method(), route,
                callback, routeSequence.getAndIncrement());
        CommandKey key = new CommandKey(owner, commandRoot);
        RouteRegistration registration;
        synchronized (aggregates) {
            Aggregate aggregate = aggregates.get(key);
            if (aggregate == null) {
                aggregate = new Aggregate(key, commandAliases, route.description());
                NativeRegistration nativeRegistration = nativeRegistration(
                        commandRoot, commandAliases, aggregate.description(), aggregate);
                aggregate.attach(nativeRegistration);
                aggregates.put(key, aggregate);
            } else if (!aggregate.aliases().equals(commandAliases)) {
                throw new IllegalArgumentException("aliases must agree for shared command root " + commandRoot);
            }
            aggregate.add(target);
            registration = new RouteRegistration(this, aggregate, target);
        }
        return registration;
    }

    public Capability capability() {
        return selectedCapability;
    }

    public static Capability selectCapability(boolean lifecycleApiAvailable) {
        // The lifecycle registrar in the pinned API has no immediate unregister operation.
        return Capability.COMMAND_MAP_FALLBACK;
    }

    static void removeKnownCommands(Map<String, Command> commands, Command command) {
        commands.entrySet().stream().filter(entry -> entry.getValue().equals(command))
                .map(Map.Entry::getKey).toList().forEach(key -> commands.remove(key, command));
    }

    int aggregateCount() {
        synchronized (aggregates) {
            return aggregates.size();
        }
    }

    private NativeRegistration nativeRegistration(
            String root, List<String> aliases, String description, Aggregate aggregate) {
        NativeRegistration registration = selectedCapability == Capability.LIFECYCLE_BRIGADIER
                ? NativeRegistration.lifecycle(root, aliases, description, aggregate, nativeRegistrations)
                : NativeRegistration.commandMap(plugin, root, aliases, description, aggregate);
        nativeRegistrations.add(registration);
        Commands registrar = lifecycleRegistrar.get();
        if (registrar != null && selectedCapability == Capability.LIFECYCLE_BRIGADIER) {
            registration.register(registrar);
        }
        return registration;
    }

    private void closeRoute(Aggregate aggregate, RouteTarget target) {
        synchronized (aggregates) {
            aggregate.remove(target);
            target.close();
            if (aggregate.empty() && aggregates.get(aggregate.key()) == aggregate) {
                aggregate.closeNative();
                aggregates.remove(aggregate.key(), aggregate);
            }
        }
    }

    private void closeRouteAfterPlatformDisable(Aggregate aggregate, RouteTarget target) {
        synchronized (aggregates) {
            aggregate.remove(target);
            target.close();
            if (aggregate.empty()) {
                aggregates.remove(aggregate.key(), aggregate);
            }
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            try {
                contexts.runGlobalAndWait(() -> {
                    synchronized (aggregates) {
                        nativeRegistrations.forEach(NativeRegistration::close);
                        nativeRegistrations.clear();
                        aggregates.clear();
                    }
                });
            } catch (RuntimeException | Error failure) {
                closed.set(false);
                throw failure;
            }
        }
    }

    private static String commandLabel(String value, String path) {
        String label = PaperDataDescriptor.text(value, "command " + path, false).toLowerCase(Locale.ROOT);
        if (!label.matches("[a-z0-9][a-z0-9_-]{0,63}")) {
            throw PaperDataDescriptor.invalid("command " + path, "is not a valid command label");
        }
        return label;
    }

    private static List<String> aliases(List<String> values) {
        Objects.requireNonNull(values, "aliases");
        List<String> result = values.stream().map(value -> commandLabel(value, "alias")).toList();
        if (new LinkedHashSet<>(result).size() != result.size()) {
            throw PaperDataDescriptor.invalid("command aliases", "must be unique");
        }
        return result;
    }

    public enum Capability { LIFECYCLE_BRIGADIER, COMMAND_MAP_FALLBACK }

    public static final class RouteRegistration implements AutoCloseable {
        private final PaperCommandBridge bridge;
        private final Aggregate aggregate;
        private final RouteTarget target;
        private final AtomicBoolean closed = new AtomicBoolean();

        private RouteRegistration(PaperCommandBridge bridge, Aggregate aggregate, RouteTarget target) {
            this.bridge = bridge;
            this.aggregate = aggregate;
            this.target = target;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                try {
                    if (bridge.closed.get()) {
                        bridge.closeRouteAfterPlatformDisable(aggregate, target);
                    } else {
                        bridge.contexts.runGlobalAndWait(() -> bridge.closeRoute(aggregate, target));
                    }
                } catch (RuntimeException | Error failure) {
                    closed.set(false);
                    throw failure;
                }
            }
        }
    }

    private final class Aggregate implements NativeDispatcher {
        private final CommandKey key;
        private final List<String> aliases;
        private final String description;
        private final List<RouteTarget> routes = new CopyOnWriteArrayList<>();
        private NativeRegistration nativeRegistration;

        private Aggregate(CommandKey key, List<String> aliases, String description) {
            this.key = key;
            this.aliases = List.copyOf(aliases);
            this.description = description.isBlank() ? "Shamoo runtime command" : description;
        }

        @Override
        public boolean execute(CommandSender sender, String alias, List<String> input) {
            Candidate selected = null;
            for (RouteTarget target : routes) {
                if (!target.route().allows(sender)) {
                    continue;
                }
                PaperCommandRoute.Match match = target.route().match(input);
                if (match == null) {
                    continue;
                }
                Candidate candidate = new Candidate(target, match);
                if (selected == null || candidate.moreSpecificThan(selected)) {
                    selected = candidate;
                }
            }
            if (selected == null) {
                return false;
            }
            if (!selected.target().route().hasPermission(sender)) {
                return true;
            }
            RouteTarget target = selected.target();
            PaperCommandRoute.Match match = selected.match();
            contexts.executeCommand(sender, alias, String.join(" ", input), match.arguments(), match.options(),
                    context -> target.invoke(List.of(context)));
            return true;
        }

        @Override
        public Collection<String> suggest(CommandSender sender, List<String> input) {
            LinkedHashSet<String> suggestions = new LinkedHashSet<>();
            routes.forEach(target -> suggestions.addAll(target.route().suggest(sender, input)));
            return List.copyOf(suggestions);
        }

        private void attach(NativeRegistration registration) {
            nativeRegistration = registration;
        }

        private void add(RouteTarget route) {
            routes.add(route);
        }

        private void remove(RouteTarget route) {
            routes.remove(route);
        }

        private boolean empty() {
            return routes.isEmpty();
        }

        private void closeNative() {
            nativeRegistration.close();
        }

        private CommandKey key() {
            return key;
        }

        private List<String> aliases() {
            return aliases;
        }

        private String description() {
            return description;
        }
    }

    private record Candidate(RouteTarget target, PaperCommandRoute.Match match) {
        private boolean moreSpecificThan(Candidate other) {
            int specificity = target.route().specificity().compareTo(other.target.route().specificity());
            return specificity > 0 || specificity == 0 && target.sequence() < other.target.sequence();
        }
    }

    private record RouteTarget(String componentId, String method, PaperCommandRoute route,
            AtomicReference<ScriptCallback> callback, long sequence) implements AutoCloseable {
        private RouteTarget(String componentId, String method, PaperCommandRoute route,
                ScriptCallback callback, long sequence) {
            this(Objects.requireNonNull(componentId, "componentId"), Objects.requireNonNull(method, "method"),
                    Objects.requireNonNull(route, "route"),
                    new AtomicReference<>(Objects.requireNonNull(callback, "callback")), sequence);
        }

        private java.util.concurrent.CompletionStage<Object> invoke(List<Object> arguments) {
            ScriptCallback current = callback.get();
            return current == null ? java.util.concurrent.CompletableFuture.failedFuture(
                    new IllegalStateException("command route is closed")) : current.invoke(arguments);
        }

        @Override
        public void close() {
            ScriptCallback current = callback.getAndSet(null);
            if (current != null) {
                current.close();
            }
        }
    }

    private record CommandKey(PluginId owner, String root) {
    }

    private interface NativeDispatcher {
        boolean execute(CommandSender sender, String alias, List<String> arguments);

        Collection<String> suggest(CommandSender sender, List<String> arguments);
    }

    private static final class NativeRegistration implements AutoCloseable {
        private final String root;
        private final List<String> aliases;
        private final String description;
        private final AtomicReference<NativeDispatcher> dispatcher;
        private final List<NativeRegistration> registrations;
        private final CommandMap map;
        private final Command command;

        private NativeRegistration(String root, List<String> aliases, String description, NativeDispatcher dispatcher,
                List<NativeRegistration> registrations, CommandMap map, Command command) {
            this.root = root;
            this.aliases = List.copyOf(aliases);
            this.description = description;
            this.dispatcher = new AtomicReference<>(Objects.requireNonNull(dispatcher, "dispatcher"));
            this.registrations = registrations;
            this.map = map;
            this.command = command;
        }

        private static NativeRegistration lifecycle(String root, List<String> aliases, String description,
                NativeDispatcher dispatcher, List<NativeRegistration> registrations) {
            return new NativeRegistration(root, aliases, description, dispatcher, registrations, null, null);
        }

        private static NativeRegistration commandMap(JavaPlugin plugin, String root, List<String> aliases,
                String description, NativeDispatcher dispatcher) {
            CommandMap map = plugin.getServer().getCommandMap();
            AtomicReference<NativeRegistration> reference = new AtomicReference<>();
            Command command = new Command(root, description, "/" + root, aliases) {
                @Override
                public boolean execute(CommandSender sender, String alias, String[] arguments) {
                    return reference.get().execute(sender, alias, List.of(arguments));
                }

                @Override
                public List<String> tabComplete(CommandSender sender, String alias, String[] arguments) {
                    return new ArrayList<>(reference.get().suggest(sender, List.of(arguments)));
                }
            };
            NativeRegistration registration = new NativeRegistration(
                    root, aliases, description, dispatcher, null, map, command);
            reference.set(registration);
            if (!map.register(plugin.getName().toLowerCase(Locale.ROOT), command)) {
                command.unregister(map);
                removeKnownCommands(map.getKnownCommands(), command);
                throw new IllegalStateException("command label is already registered: " + root);
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
                    NativeRegistration.this.execute(source.getSender(), root, List.of(arguments));
                }

                @Override
                public Collection<String> suggest(CommandSourceStack source, String[] arguments) {
                    return NativeRegistration.this.suggest(source.getSender(), List.of(arguments));
                }
            };
            registrar.register(root, description, aliases, target);
        }

        private boolean execute(CommandSender sender, String alias, List<String> arguments) {
            NativeDispatcher current = dispatcher.get();
            return current != null && current.execute(sender, alias, arguments);
        }

        private Collection<String> suggest(CommandSender sender, List<String> arguments) {
            NativeDispatcher current = dispatcher.get();
            return current == null ? List.of() : current.suggest(sender, arguments);
        }

        @Override
        public void close() {
            if (registrations != null) {
                if (dispatcher.getAndSet(null) != null) {
                    registrations.remove(this);
                }
            }
            if (command != null) {
                dispatcher.set(null);
                command.unregister(map);
                removeKnownCommands(map.getKnownCommands(), command);
            }
        }
    }
}
