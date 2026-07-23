package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventTask;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicReference;

/** Velocity event adapter that resumes the native continuation after asynchronous dispatch. */
public final class VelocityEventBridge {
    private final ProxyServer server;
    private final Object plugin;
    private final ResourceRegistry resources;

    public VelocityEventBridge(ProxyServer server, Object plugin, ResourceRegistry resources) {
        this.server = Objects.requireNonNull(server, "server");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public <E> Subscription<E> subscribe(PluginId owner, Class<E> eventType, short order,
            AsyncEventDispatcher<E> dispatcher) {
        Subscription<E> subscription = new Subscription<>(server, plugin, dispatcher);
        server.getEventManager().register(plugin, eventType, order, subscription);
        return resources.register(owner, ResourceCategory.LISTENER, eventType.getName(), subscription);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    public Subscription<?> subscribe(PluginId owner, GeneratedVelocityEventRegistry registry, String generatedType,
            short order, AsyncEventDispatcher<Object> dispatcher) {
        return subscribe(owner, (Class) registry.require(generatedType), order, dispatcher);
    }

    @FunctionalInterface
    public interface AsyncEventDispatcher<E> {
        CompletionStage<Void> dispatch(E liveEvent);
    }

    /** Stable handler registered once and replaceable without changing Velocity listener order. */
    public static final class Subscription<E> implements EventHandler<E>, AutoCloseable {
        private final ProxyServer server;
        private final Object plugin;
        private final AtomicReference<AsyncEventDispatcher<E>> dispatcher;

        private Subscription(ProxyServer server, Object plugin, AsyncEventDispatcher<E> dispatcher) {
            this.server = server;
            this.plugin = plugin;
            this.dispatcher = new AtomicReference<>(Objects.requireNonNull(dispatcher, "dispatcher"));
        }

        @Override
        public void execute(E event) {
            // Velocity calls executeAsync for handlers returning an EventTask.
        }

        @Override
        public EventTask executeAsync(E event) {
            AsyncEventDispatcher<E> current = dispatcher.get();
            CompletableFuture<Void> completion = new CompletableFuture<>();
            if (current == null) {
                completion.complete(null);
            } else {
                CompletionStage<Void> stage = current.dispatch(event);
                if (stage == null) {
                    completion.completeExceptionally(new IllegalStateException("event dispatcher returned null"));
                } else {
                    completion = adapt(stage);
                }
            }
            return EventTask.resumeWhenComplete(completion);
        }

        static CompletableFuture<Void> adapt(CompletionStage<Void> stage) {
            CompletableFuture<Void> result = new CompletableFuture<>();
            stage.whenComplete((ignored, failure) -> {
                if (failure == null) {
                    result.complete(null);
                } else {
                    result.completeExceptionally(failure);
                }
            });
            return result;
        }

        public void replaceDispatcher(AsyncEventDispatcher<E> replacement) {
            dispatcher.set(Objects.requireNonNull(replacement, "replacement"));
        }

        @Override
        public void close() {
            dispatcher.set(null);
            server.getEventManager().unregister(plugin, this);
        }
    }
}
