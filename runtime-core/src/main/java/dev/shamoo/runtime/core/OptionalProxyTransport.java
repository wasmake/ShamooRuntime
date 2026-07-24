package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.ProxyMessageCodec;
import dev.shamoo.runtime.protocol.ProxyMessageEnvelope;
import dev.shamoo.runtime.protocol.ProxyMessageType;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;

/** Optional correlated request transport over an allowlisted raw plugin-message sender. */
@SuppressWarnings("PMD.NullAssignment")
public final class OptionalProxyTransport implements AutoCloseable {
    private final ProxyMessageCodec codec = new ProxyMessageCodec();
    private final Duration timeout;
    private final Map<UUID, CompletableFuture<Response>> pending = new ConcurrentHashMap<>();
    private volatile Sender sender;

    public OptionalProxyTransport(Duration timeout) {
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must be positive");
        }
    }

    public void carrier(Sender availableSender) {
        sender = Objects.requireNonNull(availableSender, "availableSender");
    }

    public void clearCarrier() {
        sender = null;
    }

    /** Sends one already encoded canonical request frame and returns the complete correlated response frame. */
    public CompletionStage<Response> request(byte[] requestFrame) {
        ProxyMessageEnvelope request;
        try {
            request = codec.decode(Objects.requireNonNull(requestFrame, "requestFrame").clone());
        } catch (IllegalArgumentException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (request.type() != ProxyMessageType.REQUEST) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("proxy transport requires a request"));
        }
        Sender current = sender;
        if (current == null) {
            return CompletableFuture.completedFuture(Response.unavailable());
        }
        CompletableFuture<Response> result = new CompletableFuture<>();
        CompletableFuture<Response> previous = pending.putIfAbsent(request.requestId(), result);
        if (previous != null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("duplicate proxy request id"));
        }
        CompletableFuture.delayedExecutor(timeout.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS)
                .execute(() -> result.complete(Response.error(
                        "timeout [correlation=" + request.requestId() + "]")));
        result.whenComplete((ignored, failure) -> pending.remove(request.requestId(), result));
        if (!current.send(requestFrame.clone())) {
            result.complete(Response.unavailable());
        }
        return result;
    }

    /** Accepts only validated responses from the expected platform source. */
    public boolean receive(byte[] encoded, boolean validSource) {
        if (!validSource) {
            return false;
        }
        ProxyMessageEnvelope envelope;
        try {
            envelope = codec.decode(encoded);
        } catch (IllegalArgumentException exception) {
            return false;
        }
        if (envelope.type() == ProxyMessageType.REQUEST) {
            return false;
        }
        CompletableFuture<Response> result = pending.get(envelope.requestId());
        if (result == null) {
            return false;
        }
        result.complete(Response.available(encoded));
        return true;
    }

    @Override
    public void close() {
        sender = null;
        pending.values().forEach(result -> result.complete(Response.unavailable()));
        pending.clear();
    }

    @FunctionalInterface
    public interface Sender {
        boolean send(byte[] envelope);
    }

    /**
     * Explicit distinction between an absent carrier/proxy, a canonical response frame, and a local transport error.
     */
    public record Response(boolean available, byte[] payload, String error) {
        public Response {
            payload = Objects.requireNonNull(payload, "payload").clone();
        }

        @Override
        public byte[] payload() {
            return payload.clone();
        }

        public static Response available(byte[] payload) {
            return new Response(true, payload, null);
        }

        public static Response unavailable() {
            return new Response(false, new byte[0], null);
        }

        public static Response error(String error) {
            return new Response(true, new byte[0], Objects.requireNonNull(error, "error"));
        }
    }
}
