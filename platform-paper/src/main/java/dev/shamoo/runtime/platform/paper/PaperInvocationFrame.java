package dev.shamoo.runtime.platform.paper;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pumps bounded API requests on a synchronous Paper callback's originating thread. */
@SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingThrowable",
        "PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidLiteralsInIfCondition",
        "PMD.CompareObjectsWithEquals", "PMD.PreserveStackTrace"})
final class PaperInvocationFrame implements AutoCloseable {
    private final BlockingQueue<Request<?>> requests;
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean open = new AtomicBoolean(true);
    private final long deadlineNanos;
    private final String id;
    private final Thread ownerThread;

    PaperInvocationFrame(String id, Duration timeout, int maximumPending) {
        this.id = Objects.requireNonNull(id, "id");
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("Paper invocation frame timeout must be positive");
        }
        if (maximumPending < 1) {
            throw new IllegalArgumentException("Paper invocation frame queue must be positive");
        }
        ownerThread = Thread.currentThread();
        deadlineNanos = System.nanoTime() + timeout.toNanos();
        requests = new LinkedBlockingQueue<>(maximumPending);
    }

    String id() {
        return id;
    }

    <T> CompletionStage<T> call(Callable<T> action) {
        Objects.requireNonNull(action, "action");
        synchronized (lifecycleLock) {
            if (!open.get()) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Paper invocation frame has expired"));
            }
            if (Thread.currentThread() == ownerThread) {
                try {
                    return CompletableFuture.completedFuture(action.call());
                } catch (Exception exception) {
                    return CompletableFuture.failedFuture(exception);
                }
            }
            Request<T> request = new Request<>(action);
            if (!requests.offer(request)) {
                return CompletableFuture.failedFuture(
                        new IllegalStateException("Paper invocation frame queue is full"));
            }
            return request.result;
        }
    }

    Object await(CompletionStage<Object> callback) throws Exception {
        CompletableFuture<Object> result = Objects.requireNonNull(callback, "callback").toCompletableFuture();
        while (!result.isDone()) {
            long remaining = deadlineNanos - System.nanoTime();
            if (remaining <= 0) {
                TimeoutException timeout = new TimeoutException(
                        "Paper callback exceeded its synchronous frame timeout");
                result.completeExceptionally(timeout);
                throw timeout;
            }
            Request<?> request = requests.poll(Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(1)),
                    TimeUnit.NANOSECONDS);
            if (request != null) {
                request.execute();
            }
        }
        drainCompletedRequests();
        try {
            return result.join();
        } catch (CompletionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof Exception checked) {
                throw checked;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw exception;
        }
    }

    private void drainCompletedRequests() {
        Request<?> request;
        while ((request = requests.poll()) != null) {
            request.execute();
        }
    }

    @Override
    public void close() {
        synchronized (lifecycleLock) {
            if (!open.compareAndSet(true, false)) {
                return;
            }
            IllegalStateException failure = new IllegalStateException("Paper invocation frame has expired");
            Request<?> request;
            while ((request = requests.poll()) != null) {
                request.result.completeExceptionally(failure);
            }
        }
    }

    private static final class Request<T> {
        private final Callable<T> action;
        private final CompletableFuture<T> result = new CompletableFuture<>();

        private Request(Callable<T> action) {
            this.action = action;
        }

        private void execute() {
            try {
                result.complete(action.call());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        }
    }
}
