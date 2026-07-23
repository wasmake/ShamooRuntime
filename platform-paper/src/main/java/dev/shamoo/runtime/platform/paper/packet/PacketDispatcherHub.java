package dev.shamoo.runtime.platform.paper.packet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stable runtime packet dispatcher with policy-checked, owner-scoped subscriptions. */
public final class PacketDispatcherHub implements PaperPacketBridge.PacketDispatcher {
    private final PacketAccessPolicy policy;
    private final ResourceRegistry resources;
    private final java.util.List<PaperPacketBridge.PacketDispatcher> subscribers = new CopyOnWriteArrayList<>();

    public PacketDispatcherHub(PacketAccessPolicy policy, ResourceRegistry resources) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public AutoCloseable subscribe(PluginId owner, PaperPacketBridge.PacketDispatcher dispatcher) {
        policy.require(owner);
        subscribers.add(Objects.requireNonNull(dispatcher, "dispatcher"));
        AtomicBoolean closed = new AtomicBoolean();
        return resources.register(owner, ResourceCategory.LISTENER, "Paper packet dispatcher", () -> {
            if (closed.compareAndSet(false, true)) {
                subscribers.remove(dispatcher);
            }
        });
    }

    @Override
    public CompletionStage<PaperPacketBridge.Decision> dispatch(PaperPacketBridge.PacketContext packet) {
        CompletableFuture<PaperPacketBridge.Decision> result = new CompletableFuture<>();
        dispatchAt(packet, subscribers.toArray(PaperPacketBridge.PacketDispatcher[]::new), 0, result);
        return result;
    }

    private static void dispatchAt(PaperPacketBridge.PacketContext packet,
            PaperPacketBridge.PacketDispatcher[] snapshot, int index,
            CompletableFuture<PaperPacketBridge.Decision> result) {
        if (index == snapshot.length) {
            result.complete(PaperPacketBridge.Decision.pass());
            return;
        }
        CompletionStage<PaperPacketBridge.Decision> stage;
        try {
            stage = snapshot[index].dispatch(packet);
        } catch (RuntimeException failure) {
            result.completeExceptionally(failure);
            return;
        }
        if (stage == null) {
            result.completeExceptionally(new IllegalStateException("packet subscriber returned null"));
            return;
        }
        stage.whenComplete((decision, failure) -> {
            if (failure != null) {
                result.completeExceptionally(failure);
            } else if (decision == null) {
                result.completeExceptionally(new IllegalStateException("packet subscriber returned null decision"));
            } else if (decision.cancelled() || decision.replacement() != null) {
                result.complete(decision);
            } else {
                dispatchAt(packet, snapshot, index + 1, result);
            }
        });
    }
}
