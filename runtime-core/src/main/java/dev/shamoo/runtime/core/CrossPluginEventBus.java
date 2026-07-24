package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.EventContract;
import dev.shamoo.runtime.protocol.SemverRange;
import dev.shamoo.runtime.protocol.ServiceContract;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Version-filtering event bus whose subscriptions follow plugin generation ownership. */
@SuppressWarnings({"PMD.CloseResource", "PMD.AvoidLiteralsInIfCondition"})
public final class CrossPluginEventBus {
    private final Set<Subscription> subscriptions = ConcurrentHashMap.newKeySet();
    private final Set<UUID> activeGenerations = ConcurrentHashMap.newKeySet();

    PluginEvents scoped(UUID generation, PluginId owner, InvocationController admission,
            ResourceRegistry resources) {
        return new PluginEvents() {
            @Override
            public AutoCloseable subscribe(String eventName, SemverRange versions, PluginEventHandler handler) {
                Subscription subscription = new Subscription(generation, admission,
                        ServiceContract.contractName(eventName), versions, handler);
                subscriptions.add(subscription);
                return resources.register(owner, ResourceCategory.LISTENER, eventName, subscription);
            }

            @Override
            public CompletionStage<Void> publish(EventContract contract, Object payload) {
                return CrossPluginEventBus.this.publish(contract, payload);
            }
        };
    }

    void activate(UUID generation) {
        activeGenerations.add(generation);
    }

    void deactivate(UUID generation) {
        activeGenerations.remove(generation);
    }

    private CompletionStage<Void> publish(EventContract contract, Object payload) {
        Objects.requireNonNull(contract, "contract");
        List<CompletableFuture<Void>> deliveries = new java.util.ArrayList<>();
        for (Subscription subscription : subscriptions) {
            if (subscription.accepts(contract)) {
                deliveries.add(adapt(subscription.deliver(payload)));
            }
        }
        if (deliveries.isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        if (deliveries.size() == 1) {
            return deliveries.getFirst();
        }
        return CompletableFuture.allOf(deliveries.toArray(CompletableFuture[]::new));
    }

    private static CompletableFuture<Void> adapt(CompletionStage<Void> stage) {
        if (stage instanceof CompletableFuture<Void> future) {
            return future;
        }
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

    private final class Subscription implements AutoCloseable {
        private final UUID generation;
        private final InvocationController admission;
        private final String eventName;
        private final SemverRange versions;
        private final PluginEventHandler handler;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Subscription(UUID generation, InvocationController admission, String eventName,
                SemverRange versions, PluginEventHandler handler) {
            this.generation = Objects.requireNonNull(generation, "generation");
            this.admission = Objects.requireNonNull(admission, "admission");
            this.eventName = eventName;
            this.versions = Objects.requireNonNull(versions, "versions");
            this.handler = Objects.requireNonNull(handler, "handler");
        }

        private boolean accepts(EventContract contract) {
            return !closed.get() && activeGenerations.contains(generation)
                    && eventName.equals(contract.name()) && versions.includes(contract.version());
        }

        private CompletionStage<Void> deliver(Object payload) {
            InvocationAdmission.Lease lease;
            try {
                lease = admission.admit();
            } catch (InvocationRejectedError error) {
                return CompletableFuture.completedFuture(null);
            }
            CompletionStage<Void> delivery;
            try {
                delivery = Objects.requireNonNull(handler.handle(payload), "event handler returned null");
            } catch (RuntimeException exception) {
                lease.close();
                return CompletableFuture.failedFuture(exception);
            }
            return delivery.whenComplete((ignored, failure) -> lease.close());
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                subscriptions.remove(this);
            }
        }
    }
}
