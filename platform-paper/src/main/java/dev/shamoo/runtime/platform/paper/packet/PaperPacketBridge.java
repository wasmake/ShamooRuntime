package dev.shamoo.runtime.platform.paper.packet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import io.netty.channel.Channel;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.util.ReferenceCountUtil;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashSet;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;

/** Per-connection, event-loop-safe packet interception over exact-version generated handles. */
@SuppressWarnings({
    "PMD.AvoidLiteralsInIfCondition",
    "PMD.CompareObjectsWithEquals",
    "PMD.NullAssignment"
})
public final class PaperPacketBridge {
    public static final String PACKET_HANDLER_ANCHOR = "packet_handler";
    private static final String HANDLER_NAME = "shamoo_packet_bridge";
    private final PacketAccessPolicy policy;
    private final PacketRegistry registry;
    private final ResourceRegistry resources;
    private final Duration timeout;
    private final int maximumPending;

    public PaperPacketBridge(PacketAccessPolicy policy, PacketRegistry registry, ResourceRegistry resources,
            Duration timeout, int maximumPending) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.resources = Objects.requireNonNull(resources, "resources");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative() || timeout.isZero() || maximumPending < 1) {
            throw new IllegalArgumentException("packet timeout and pending capacity must be positive");
        }
        this.maximumPending = maximumPending;
    }

    public Injection inject(PluginId owner, Channel channel, String pipelineAnchor, PacketDispatcher dispatcher) {
        return inject(owner, channel, pipelineAnchor, dispatcher, packet -> packet.packet().descriptor()
                .registrations().getFirst());
    }

    public Injection inject(PluginId owner, Channel channel, String pipelineAnchor, PacketDispatcher dispatcher,
            RegistrationProvider registrations) {
        policy.require(owner);
        Injection injection = new Injection(channel, pipelineAnchor, dispatcher, registrations);
        if (channel.pipeline().context(pipelineAnchor) == null) {
            throw new IllegalStateException("Paper packet pipeline anchor is absent: " + pipelineAnchor);
        }
        if (!channel.isRegistered() || channel.eventLoop().inEventLoop()) {
            injection.install();
        } else {
            channel.eventLoop().submit(injection::install).syncUninterruptibly();
        }
        return resources.register(owner, ResourceCategory.PROXY, "Paper NMS packet interceptor", injection);
    }

    public Injection injectFirst(PluginId owner, Channel channel, PacketDispatcher dispatcher,
            RegistrationProvider registrations) {
        policy.require(owner);
        Injection injection = new Injection(channel, null, dispatcher, registrations);
        if (!channel.isRegistered() || channel.eventLoop().inEventLoop()) {
            injection.install();
        } else {
            channel.eventLoop().submit(injection::install).syncUninterruptibly();
        }
        return resources.register(owner, ResourceCategory.PROXY, "Paper early NMS packet interceptor", injection);
    }

    public void send(PluginId owner, Channel channel, PacketHandle packet) {
        policy.require(owner);
        channel.eventLoop().execute(() -> channel.writeAndFlush(registry.unwrap(packet)));
    }

    public void send(PluginId owner, NativeConnection connection, PacketHandle packet) {
        policy.require(owner);
        connection.send(registry.unwrap(packet));
    }

    @FunctionalInterface
    public interface NativeConnection {
        void send(Object packet);
    }

    @FunctionalInterface
    public interface PacketDispatcher {
        CompletionStage<Decision> dispatch(PacketContext packet);
    }

    @FunctionalInterface
    public interface RegistrationProvider {
        PacketRegistry.PacketRegistration current(PacketContext packet);
    }

    public record PacketContext(Direction direction, PacketRegistry.Phase phase,
            PacketRegistry.Direction protocolDirection, int protocolId, PacketHandle packet) {
        PacketContext(Direction direction, PacketHandle packet, PacketRegistry.PacketRegistration registration) {
            this(direction, registration.phase(), registration.direction(),
                    Objects.requireNonNull(registration.protocolId(), "packet protocol id"), packet);
        }

        PacketContext(Direction direction, PacketHandle packet) {
            this(direction, packet.descriptor().registrations().getFirst().phase(),
                    packet.descriptor().registrations().getFirst().direction(),
                    Objects.requireNonNull(packet.descriptor().registrations().getFirst().protocolId()), packet);
        }

        public PacketRegistry.PacketRegistration registration() {
            return new PacketRegistry.PacketRegistration(phase, protocolDirection, protocolId);
        }
    }

    public enum Direction { INBOUND, OUTBOUND }

    public record Decision(boolean cancelled, PacketHandle replacement, boolean allowCrossRegistration) {
        public Decision(boolean cancelled, PacketHandle replacement) {
            this(cancelled, replacement, false);
        }

        public static Decision pass() {
            return new Decision(false, null, false);
        }

        public static Decision cancel() {
            return new Decision(true, null, false);
        }

        public static Decision replaceAcrossRegistrations(PacketHandle replacement) {
            return new Decision(false, Objects.requireNonNull(replacement, "replacement"), true);
        }
    }

    public final class Injection extends ChannelDuplexHandler implements AutoCloseable {
        private final Channel channel;
        private final String anchor;
        private final AtomicReference<PacketDispatcher> dispatcher;
        private final RegistrationProvider registrations;
        private final Deque<PendingPacket> inbound = new ArrayDeque<>();
        private final Deque<PendingPacket> outbound = new ArrayDeque<>();
        private final Set<CompletableFuture<Decision>> inFlight = new HashSet<>();
        private final AtomicInteger pending = new AtomicInteger();
        private volatile boolean closed;

        private Injection(Channel channel, String anchor, PacketDispatcher dispatcher,
                RegistrationProvider registrations) {
            this.channel = Objects.requireNonNull(channel, "channel");
            this.anchor = anchor == null ? null : pipelineAnchor(anchor);
            this.dispatcher = new AtomicReference<>(Objects.requireNonNull(dispatcher, "dispatcher"));
            this.registrations = Objects.requireNonNull(registrations, "registrations");
        }

        private String pipelineAnchor(String value) {
            if (value.isBlank()) {
                throw new IllegalArgumentException("pipeline anchor must not be blank");
            }
            return value;
        }

        private void install() {
            if (anchor == null) {
                channel.pipeline().addFirst(HANDLER_NAME, this);
            } else {
                channel.pipeline().addBefore(anchor, HANDLER_NAME, this);
            }
            channel.closeFuture().addListener(ignored -> close());
        }

        public void replaceDispatcher(PacketDispatcher replacement) {
            dispatcher.set(Objects.requireNonNull(replacement, "replacement"));
        }

        @Override
        public void channelRead(ChannelHandlerContext context, Object message) {
            intercept(context, message, Direction.INBOUND, null);
        }

        @Override
        public void write(ChannelHandlerContext context, Object message, ChannelPromise promise) {
            intercept(context, message, Direction.OUTBOUND, promise);
        }

        @Override
        public void flush(ChannelHandlerContext context) {
            if (closed || outbound.isEmpty()) {
                context.flush();
            } else {
                outbound.addLast(new PendingPacket(context, null, null, null, true));
            }
        }

        private void intercept(ChannelHandlerContext context, Object message, Direction direction,
                ChannelPromise promise) {
            if (closed || dispatcher.get() == null) {
                forward(context, direction, message, promise);
                return;
            }
            PacketHandle handle;
            try {
                handle = registry.wrap(message);
            } catch (SecurityException ignored) {
                forward(context, direction, message, promise);
                return;
            }
            if (pending.get() >= maximumPending) {
                ReferenceCountUtil.release(message);
                fail(context, promise, new IllegalStateException("packet interceptor backpressure limit exceeded"));
                return;
            }
            PacketContext packetContext;
            try {
                PacketContext partial = new PacketContext(direction, handle);
                PacketRegistry.PacketRegistration registration = registrations.current(partial);
                registry.requireRegistration(handle, registration.phase(), registration.direction());
                packetContext = new PacketContext(direction, handle, registration);
            } catch (RuntimeException exception) {
                ReferenceCountUtil.release(message);
                fail(context, promise, exception);
                return;
            }
            Deque<PendingPacket> queue = direction == Direction.INBOUND ? inbound : outbound;
            queue.addLast(new PendingPacket(context, message, promise, packetContext, false));
            pending.incrementAndGet();
            if (queue.size() == 1) {
                dispatchNext(queue, direction);
            }
        }

        private void dispatchNext(Deque<PendingPacket> queue, Direction direction) {
            PendingPacket item = queue.peekFirst();
            if (item == null) {
                return;
            }
            if (item.flush) {
                item.context.flush();
                queue.removeFirst();
                dispatchNext(queue, direction);
                return;
            }
            PacketDispatcher current = dispatcher.get();
            if (closed || current == null) {
                finishClosed(queue, item);
                return;
            }
            CompletionStage<Decision> dispatched;
            try {
                dispatched = current.dispatch(item.packet);
            } catch (RuntimeException exception) {
                complete(queue, direction, item, null, exception);
                return;
            }
            if (dispatched == null) {
                complete(queue, direction, item, null,
                        new IllegalStateException("packet dispatcher returned a null stage"));
                return;
            }
            CompletableFuture<Decision> bounded = new CompletableFuture<>();
            inFlight.add(bounded);
            dispatched.whenComplete((decision, failure) -> {
                if (failure == null) {
                    bounded.complete(decision);
                } else {
                    bounded.completeExceptionally(failure);
                }
            });
            bounded.orTimeout(timeout.toMillis(), TimeUnit.MILLISECONDS)
                    .whenComplete((decision, failure) -> item.context.executor().execute(() ->
                            complete(queue, direction, item, decision, failure)));
        }

        private void complete(Deque<PendingPacket> queue, Direction direction, PendingPacket item,
                Decision decision, Throwable failure) {
            if (queue.peekFirst() != item) {
                return;
            }
            inFlight.removeIf(CompletableFuture::isDone);
            queue.removeFirst();
            pending.decrementAndGet();
            if (closed) {
                ReferenceCountUtil.release(item.message);
                if (item.promise != null) {
                    item.promise.tryFailure(new IllegalStateException("packet interceptor is closed"));
                }
            } else {
                if (failure != null) {
                    ReferenceCountUtil.release(item.message);
                    fail(item.context, item.promise, failure instanceof TimeoutException ? failure
                            : new IllegalStateException("packet dispatch failed", failure));
                } else if (decision == null) {
                    ReferenceCountUtil.release(item.message);
                    fail(item.context, item.promise,
                            new IllegalStateException("packet dispatcher returned a null decision"));
                } else {
                    try {
                        if (!decision.cancelled()) {
                            Object replacement = decision.replacement() == null
                                    ? item.message : registry.unwrapReplacement(decision.replacement(),
                                            item.packet.registration(),
                                            decision.allowCrossRegistration());
                            if (replacement != item.message) {
                                ReferenceCountUtil.release(item.message);
                            }
                            forward(item.context, direction, replacement, item.promise);
                        } else {
                            ReferenceCountUtil.release(item.message);
                            if (item.promise != null) {
                                item.promise.trySuccess();
                            }
                        }
                    } catch (RuntimeException exception) {
                        ReferenceCountUtil.release(item.message);
                        fail(item.context, item.promise, exception);
                    }
                }
            }
            dispatchNext(queue, direction);
        }

        private void finishClosed(Deque<PendingPacket> queue, PendingPacket item) {
            queue.removeFirst();
            if (!item.flush) {
                pending.decrementAndGet();
                ReferenceCountUtil.release(item.message);
                if (item.promise != null) {
                    item.promise.tryFailure(new IllegalStateException("packet interceptor is closed"));
                }
            }
        }

        private void forward(ChannelHandlerContext context, Direction direction, Object value, ChannelPromise promise) {
            if (direction == Direction.INBOUND) {
                context.fireChannelRead(value);
            } else {
                context.write(value, promise);
            }
        }

        private void fail(ChannelHandlerContext context, ChannelPromise promise, Throwable failure) {
            if (promise != null) {
                promise.tryFailure(failure);
            } else {
                context.fireExceptionCaught(failure);
            }
        }

        @Override
        public void close() {
            dispatcher.set(null);
            if (channel.eventLoop().inEventLoop()) {
                remove();
            } else {
                channel.eventLoop().submit(this::remove).syncUninterruptibly();
            }
        }

        private void remove() {
            closed = true;
            inFlight.forEach(future -> future.cancel(false));
            inFlight.clear();
            drain(inbound);
            drain(outbound);
            if (channel.pipeline().context(this) != null) {
                channel.pipeline().remove(this);
            }
        }

        private void drain(Deque<PendingPacket> queue) {
            PendingPacket item = queue.pollFirst();
            while (item != null) {
                if (!item.flush) {
                    pending.decrementAndGet();
                    ReferenceCountUtil.release(item.message);
                    if (item.promise != null) {
                        item.promise.tryFailure(new IllegalStateException("packet interceptor is closed"));
                    }
                }
                item = queue.pollFirst();
            }
        }

        private record PendingPacket(ChannelHandlerContext context, Object message, ChannelPromise promise,
                PacketContext packet, boolean flush) {
        }
    }
}
