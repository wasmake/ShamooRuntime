package dev.shamoo.runtime.core;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Atomic invocation admission and drain accounting for one plugin. */
public final class InvocationAdmission implements InvocationController {
    private final PluginId pluginId;
    private final List<CompletableFuture<Void>> drainWaiters = new ArrayList<>();
    private boolean accepting;
    private int active;
    private long admitted;
    private long completed;
    private long rejected;

    public InvocationAdmission(PluginId pluginId) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
    }

    public synchronized void open() {
        accepting = true;
    }

    public synchronized void stop() {
        accepting = false;
    }

    @Override
    public synchronized Lease admit() {
        if (!accepting) {
            rejected++;
            throw new InvocationRejectedError(pluginId);
        }
        active++;
        admitted++;
        return new Lease(this);
    }

    public synchronized CompletionStage<Void> awaitDrained(Duration timeout) {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
        if (active == 0) {
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> waiter = new CompletableFuture<>();
        drainWaiters.add(waiter);
        waiter.whenComplete((ignored, failure) -> removeWaiter(waiter));
        return waiter.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized InvocationSnapshot snapshot() {
        return new InvocationSnapshot(accepting, active, admitted, completed, rejected);
    }

    private synchronized void complete() {
        if (active <= 0) {
            throw new IllegalStateException("invocation lease completed more than once");
        }
        active--;
        completed++;
        if (active == 0) {
            List<CompletableFuture<Void>> waiters = List.copyOf(drainWaiters);
            drainWaiters.clear();
            waiters.forEach(waiter -> waiter.complete(null));
        }
    }

    private synchronized void removeWaiter(CompletableFuture<Void> waiter) {
        drainWaiters.remove(waiter);
    }

    /** Idempotent active-invocation lease. */
    public static final class Lease implements AutoCloseable {
        private final InvocationAdmission owner;
        private final AtomicBoolean closed = new AtomicBoolean();

        private Lease(InvocationAdmission owner) {
            this.owner = owner;
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                owner.complete();
            }
        }
    }
}
