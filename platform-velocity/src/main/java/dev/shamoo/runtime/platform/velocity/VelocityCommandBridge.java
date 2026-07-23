package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.command.RawCommand;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;

/** Registers Velocity Brigadier, simple, and raw commands with owned unregister handles. */
public final class VelocityCommandBridge {
    private final ProxyServer server;
    private final ResourceRegistry resources;

    public VelocityCommandBridge(ProxyServer server, ResourceRegistry resources) {
        this.server = Objects.requireNonNull(server, "server");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public Registration<SimpleDispatcher> registerSimple(
            PluginId owner, CommandMeta metadata, SimpleDispatcher dispatcher) {
        AtomicReference<SimpleDispatcher> target = new AtomicReference<>(dispatcher);
        SimpleCommand command = invocation -> {
            SimpleDispatcher current = target.get();
            if (current != null) {
                current.execute(invocation);
            }
        };
        return register(owner, metadata, command, target);
    }

    public Registration<RawDispatcher> registerRaw(PluginId owner, CommandMeta metadata, RawDispatcher dispatcher) {
        AtomicReference<RawDispatcher> target = new AtomicReference<>(dispatcher);
        RawCommand command = invocation -> {
            RawDispatcher current = target.get();
            if (current != null) {
                current.execute(invocation);
            }
        };
        return register(owner, metadata, command, target);
    }

    public Registration<Void> registerBrigadier(PluginId owner, BrigadierCommand command) {
        CommandMeta metadata = server.getCommandManager().metaBuilder(command).build();
        server.getCommandManager().register(metadata, command);
        Registration<Void> registration = new Registration<>(server, metadata, null);
        return resources.register(owner, ResourceCategory.COMMAND, command.getNode().getName(), registration);
    }

    private <D> Registration<D> register(
            PluginId owner, CommandMeta metadata, Command command, AtomicReference<D> target) {
        server.getCommandManager().register(metadata, command);
        Registration<D> registration = new Registration<>(server, metadata, target);
        return resources.register(owner, ResourceCategory.COMMAND, metadata.getAliases().toString(), registration);
    }

    @FunctionalInterface
    public interface SimpleDispatcher {
        void execute(SimpleCommand.Invocation invocation);
    }

    @FunctionalInterface
    public interface RawDispatcher {
        void execute(RawCommand.Invocation invocation);
    }

    public static final class Registration<D> implements AutoCloseable {
        private final ProxyServer server;
        private final CommandMeta metadata;
        private final AtomicReference<D> dispatcher;

        private Registration(ProxyServer server, CommandMeta metadata, AtomicReference<D> dispatcher) {
            this.server = server;
            this.metadata = metadata;
            this.dispatcher = dispatcher;
        }

        public void replaceDispatcher(D replacement) {
            if (dispatcher == null) {
                throw new IllegalStateException("Brigadier command tree cannot be replaced in place");
            }
            dispatcher.set(Objects.requireNonNull(replacement, "replacement"));
        }

        @Override
        public void close() {
            if (dispatcher != null) {
                dispatcher.set(null);
            }
            server.getCommandManager().unregister(metadata);
        }
    }
}
