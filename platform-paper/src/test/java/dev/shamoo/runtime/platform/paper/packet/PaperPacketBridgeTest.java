package dev.shamoo.runtime.platform.paper.packet;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.Direction;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.PacketDescriptor;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.PacketRegistration;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.Phase;
import io.netty.channel.ChannelDuplexHandler;
import io.netty.channel.ChannelFuture;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import java.time.Duration;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.ArrayList;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.CloseResource",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.UnitTestContainsTooManyAsserts"
})
class PaperPacketBridgeTest {
    private static final PluginId OWNER = new PluginId("packets");

    @Test
    void outboundCancellationCompletesPromiseWithoutForwarding() {
        Fixture fixture = new Fixture(Duration.ofSeconds(1));
        fixture.bridge.inject(OWNER, fixture.channel, "packet_handler",
                packet -> CompletableFuture.completedFuture(PaperPacketBridge.Decision.cancel()));
        fixture.channel.runPendingTasks();

        ChannelFuture result = fixture.channel.writeAndFlush(fixture.original);
        fixture.channel.runPendingTasks();
        fixture.channel.runPendingTasks();

        assertTrue(result.isSuccess(), () -> String.valueOf(result.cause()));
        assertFalse(fixture.channel.outboundMessages().contains(fixture.original));
    }

    @Test
    void outboundReplacementCompletesOriginalPromise() {
        Fixture fixture = new Fixture(Duration.ofSeconds(1));
        PacketHandle replacement = fixture.registry.wrap(fixture.replacement);
        fixture.bridge.inject(OWNER, fixture.channel, "packet_handler",
                packet -> CompletableFuture.completedFuture(new PaperPacketBridge.Decision(false, replacement)));
        fixture.channel.runPendingTasks();

        ChannelFuture result = fixture.channel.writeAndFlush(fixture.original);
        fixture.channel.runPendingTasks();
        fixture.channel.runPendingTasks();

        assertTrue(result.isSuccess(), () -> String.valueOf(result.cause()));
        assertSame(fixture.replacement, fixture.channel.readOutbound());
    }

    @Test
    void timeoutFailsOutboundPromiseAndDoesNotForward() throws InterruptedException {
        Fixture fixture = new Fixture(Duration.ofMillis(10));
        fixture.bridge.inject(OWNER, fixture.channel, "packet_handler", packet -> new CompletableFuture<>());
        fixture.channel.runPendingTasks();

        ChannelFuture result = fixture.channel.writeAndFlush(fixture.original);
        Thread.sleep(30);
        fixture.channel.runPendingTasks();

        assertTrue(result.isDone());
        assertFalse(result.isSuccess());
        assertFalse(fixture.channel.outboundMessages().contains(fixture.original));
    }

    @Test
    void serializesDecisionsInPacketOrder() {
        Fixture fixture = new Fixture(Duration.ofSeconds(1));
        List<CompletableFuture<PaperPacketBridge.Decision>> decisions = new ArrayList<>();
        fixture.bridge.inject(OWNER, fixture.channel, "packet_handler", packet -> {
            CompletableFuture<PaperPacketBridge.Decision> decision = new CompletableFuture<>();
            decisions.add(decision);
            return decision;
        });
        fixture.channel.write(fixture.original);
        fixture.channel.write(fixture.replacement);
        fixture.channel.flush();
        fixture.channel.runPendingTasks();

        assertEquals(1, decisions.size());
        decisions.getFirst().complete(PaperPacketBridge.Decision.pass());
        fixture.channel.runPendingTasks();
        assertEquals(2, decisions.size());
        assertNull(fixture.channel.readOutbound());
        decisions.get(1).complete(PaperPacketBridge.Decision.pass());
        fixture.channel.runPendingTasks();
        assertSame(fixture.original, fixture.channel.readOutbound());
        assertSame(fixture.replacement, fixture.channel.readOutbound());
    }

    @Test
    void cancellationAndCloseReleaseReferenceCountedPackets() {
        EmbeddedChannel channel = new EmbeddedChannel();
        channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
        ByteBuf cancelled = Unpooled.buffer().writeByte(1);
        CompletableFuture<PaperPacketBridge.Decision> pending = new CompletableFuture<>();
        PacketRegistry registry = new PacketRegistry("1.21.8", List.of(new PacketDescriptor(
                "1.21.8:buffer", cancelled.getClass(),
                List.of(new PacketRegistration(Phase.PLAY, Direction.CLIENTBOUND, 7)))));
        PaperPacketBridge bridge = new PaperPacketBridge(new PacketAccessPolicy(true, Set.of(OWNER)), registry,
                new ResourceRegistry(), Duration.ofSeconds(1), 8);
        PaperPacketBridge.Injection injection = bridge.inject(OWNER, channel, "packet_handler",
                packet -> pending);
        channel.write(cancelled);
        channel.runPendingTasks();

        injection.close();
        pending.complete(PaperPacketBridge.Decision.pass());
        channel.runPendingTasks();

        assertEquals(0, cancelled.refCnt());
        assertNull(channel.readOutbound());
    }

    private static final class Fixture {
        private final Message original = new Message("original");
        private final Message replacement = new Message("replacement");
        private final EmbeddedChannel channel = new EmbeddedChannel();
        private final PacketRegistry registry = new PacketRegistry("1.21.8", List.of(new PacketDescriptor(
                "1.21.8:message", Message.class,
                List.of(new PacketRegistration(Phase.PLAY, Direction.CLIENTBOUND, 0)))));
        private final PaperPacketBridge bridge;

        private Fixture(Duration timeout) {
            channel.pipeline().addLast("packet_handler", new ChannelDuplexHandler());
            bridge = new PaperPacketBridge(new PacketAccessPolicy(true, Set.of(OWNER)), registry,
                    new ResourceRegistry(), timeout, 8);
        }
    }

    private record Message(String value) {
    }
}
