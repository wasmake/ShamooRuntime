package dev.shamoo.runtime.platform.paper.packet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicBoolean;

/** Stable runtime packet dispatcher with policy-checked, owner-scoped subscriptions. */
@SuppressWarnings("PMD.CompareObjectsWithEquals")
public final class PacketDispatcherHub implements PaperPacketBridge.PacketDispatcher {
    private final PacketAccessPolicy policy;
    private final ResourceRegistry resources;
    private volatile PaperPacketBridge.PacketDispatcher[] subscribers = {};

    public PacketDispatcherHub(PacketAccessPolicy policy, ResourceRegistry resources) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public synchronized AutoCloseable subscribe(PluginId owner, PaperPacketBridge.PacketDispatcher dispatcher) {
        policy.require(owner);
        PaperPacketBridge.PacketDispatcher value = Objects.requireNonNull(dispatcher, "dispatcher");
        PaperPacketBridge.PacketDispatcher[] updated = java.util.Arrays.copyOf(subscribers, subscribers.length + 1);
        updated[updated.length - 1] = value;
        subscribers = updated;
        AtomicBoolean closed = new AtomicBoolean();
        return resources.register(owner, ResourceCategory.LISTENER, "Paper packet dispatcher", () -> {
            if (closed.compareAndSet(false, true)) {
                remove(value);
            }
        });
    }

    @Override
    public CompletionStage<PaperPacketBridge.Decision> dispatch(PaperPacketBridge.PacketContext packet) {
        CompletableFuture<PaperPacketBridge.Decision> result = new CompletableFuture<>();
        dispatchAt(packet, subscribers, 0, result);
        return result;
    }

    private synchronized void remove(PaperPacketBridge.PacketDispatcher dispatcher) {
        PaperPacketBridge.PacketDispatcher[] current = subscribers;
        for (int index = 0; index < current.length; index++) {
            if (current[index] == dispatcher) {
                PaperPacketBridge.PacketDispatcher[] updated =
                        new PaperPacketBridge.PacketDispatcher[current.length - 1];
                System.arraycopy(current, 0, updated, 0, index);
                System.arraycopy(current, index + 1, updated, index, current.length - index - 1);
                subscribers = updated;
                return;
            }
        }
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
