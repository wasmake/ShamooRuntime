package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.plugin.java.JavaPlugin;

/** Registers live Paper events while keeping a replaceable dispatcher identity. */
public final class PaperEventBridge {
    private final JavaPlugin plugin;
    private final ResourceRegistry resources;

    public PaperEventBridge(JavaPlugin plugin, ResourceRegistry resources) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public Subscription subscribe(PluginId owner, Class<? extends Event> eventType, EventPriority priority,
            boolean receiveCancelled, SynchronousEventDispatcher dispatcher) {
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(priority, "priority");
        Listener listener = new Listener() { };
        Subscription subscription = new Subscription(listener, dispatcher);
        plugin.getServer().getPluginManager().registerEvent(eventType, listener, priority, (ignored, event) -> {
            try {
                subscription.dispatch(event);
            } catch (Exception exception) {
                throw new EventException(exception);
            }
        }, plugin, !receiveCancelled);
        return resources.register(owner, ResourceCategory.LISTENER, eventType.getName(), subscription);
    }

    public Subscription subscribe(PluginId owner, GeneratedPaperEventRegistry registry, String generatedType,
            EventPriority priority, boolean receiveCancelled, SynchronousEventDispatcher dispatcher) {
        return subscribe(owner, registry.require(generatedType), priority, receiveCancelled, dispatcher);
    }

    /** Invocation must finish synchronously so cancellation/result mutation remains in the originating event frame. */
    @FunctionalInterface
    public interface SynchronousEventDispatcher {
        void dispatch(Event event) throws Exception;
    }

    /** Stable registered listener whose dispatch target can be atomically replaced during reload. */
    public static final class Subscription implements AutoCloseable {
        private final Listener listener;
        private final AtomicReference<SynchronousEventDispatcher> dispatcher;

        private Subscription(Listener listener, SynchronousEventDispatcher dispatcher) {
            this.listener = listener;
            this.dispatcher = new AtomicReference<>(Objects.requireNonNull(dispatcher, "dispatcher"));
        }

        public void replaceDispatcher(SynchronousEventDispatcher replacement) {
            dispatcher.set(Objects.requireNonNull(replacement, "replacement"));
        }

        private void dispatch(Event event) throws Exception {
            SynchronousEventDispatcher current = dispatcher.get();
            if (current != null) {
                current.dispatch(event);
            }
        }

        @Override
        public void close() {
            dispatcher.set(null);
            HandlerList.unregisterAll(listener);
        }
    }
}
