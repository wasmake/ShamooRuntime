package dev.shamoo.runtime.platform.paper.nms;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.platform.paper.packet.PaperPacketBridge;
import dev.shamoo.runtime.platform.paper.packet.PaperPacketBridge.Injection;
import dev.shamoo.runtime.platform.paper.packet.PaperPacketBridge.PacketDispatcher;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerConnectionListener;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

/** Hooks accepted channels before handshake decoding and owns every connection interceptor. */
public final class PaperNmsInjectionManager implements AutoCloseable {
    private static final String ACCEPT_HANDLER = "shamoo_connection_acceptor";
    private static final String INITIALIZER_HANDLER = "shamoo_connection_initializer";
    private final PluginId owner;
    private final PaperPacketBridge bridge;
    private final PacketDispatcher dispatcher;
    private final Map<Channel, Injection> injections = new ConcurrentHashMap<>();
    private final Map<Channel, ChannelHandler> acceptors = new ConcurrentHashMap<>();

    public PaperNmsInjectionManager(JavaPlugin plugin, PluginId owner, PaperPacketBridge bridge,
            PacketDispatcher dispatcher) {
        Objects.requireNonNull(plugin, "plugin");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.bridge = Objects.requireNonNull(bridge, "bridge");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher");
    }

    public void start() {
        PaperNmsCompatibility.requireCompatibleServer();
        for (ChannelFuture listener : listenerChannels(MinecraftServer.getServer().getConnection())) {
            installAcceptor(listener.channel());
        }
        MinecraftServer.getServer().getConnection().getConnections().forEach(connection -> inject(connection.channel));
    }

    public void send(Player player, dev.shamoo.runtime.platform.paper.packet.PacketHandle packet) {
        bridge.send(owner, PaperNmsConnection.sender(player), packet);
    }

    private void installAcceptor(Channel listener) {
        ChannelInboundHandlerAdapter acceptor = new ChannelInboundHandlerAdapter() {
            @Override
            public void channelRead(ChannelHandlerContext context, Object message) {
                if (message instanceof Channel child) {
                    child.pipeline().addLast(INITIALIZER_HANDLER, new ChannelInboundHandlerAdapter() {
                        @Override
                        public void channelRegistered(ChannelHandlerContext childContext) {
                            inject(child);
                            child.pipeline().remove(this);
                            childContext.fireChannelRegistered();
                        }
                    });
                }
                context.fireChannelRead(message);
            }
        };
        Runnable install = () -> {
            String nativeAcceptor = listener.pipeline().names().stream().filter(name -> {
                ChannelHandler handler = listener.pipeline().get(name);
                return handler != null && handler.getClass().getName().contains("ServerBootstrapAcceptor");
            }).findFirst().orElseThrow(() -> new IllegalStateException("Netty server acceptor is absent"));
            listener.pipeline().addBefore(nativeAcceptor, ACCEPT_HANDLER, acceptor);
            acceptors.put(listener, acceptor);
        };
        if (listener.eventLoop().inEventLoop()) {
            install.run();
        } else {
            listener.eventLoop().submit(install).syncUninterruptibly();
        }
    }

    private void inject(Channel channel) {
        injections.computeIfAbsent(channel, current -> {
            Injection injection = bridge.inject(owner, current, PaperPacketBridge.PACKET_HANDLER_ANCHOR, dispatcher,
                    packet -> PaperNmsConnection.registration(current, packet));
            current.closeFuture().addListener(ignored -> {
                Injection removed = injections.remove(current);
                if (removed != null) {
                    removed.close();
                }
            });
            return injection;
        });
    }

    @SuppressWarnings({"unchecked", "PMD.AvoidAccessibilityAlteration"})
    private static List<ChannelFuture> listenerChannels(ServerConnectionListener listener) {
        try {
            Field field = ServerConnectionListener.class.getDeclaredField("channels");
            field.setAccessible(true);
            return List.copyOf((List<ChannelFuture>) field.get(listener));
        } catch (ReflectiveOperationException exception) {
            throw new IllegalStateException("pinned Paper listener layout changed", exception);
        }
    }

    @Override
    public void close() {
        acceptors.forEach((channel, handler) -> channel.eventLoop().submit(() -> {
            if (channel.pipeline().context(handler) != null) {
                channel.pipeline().remove(handler);
            }
        }).syncUninterruptibly());
        acceptors.clear();
        injections.values().forEach(Injection::close);
        injections.clear();
    }
}
