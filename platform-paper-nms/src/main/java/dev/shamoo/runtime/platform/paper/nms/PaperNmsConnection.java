package dev.shamoo.runtime.platform.paper.nms;

import dev.shamoo.runtime.platform.paper.packet.PaperPacketBridge.NativeConnection;
import io.netty.channel.Channel;
import net.minecraft.network.Connection;
import net.minecraft.network.ConnectionProtocol;
import net.minecraft.network.protocol.Packet;
import net.minecraft.server.level.ServerPlayer;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import dev.shamoo.runtime.platform.paper.packet.PaperPacketBridge.PacketContext;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry;

/** Direct Mojang-mapped Player to connection/channel access for the pinned server. */
public final class PaperNmsConnection {
    private PaperNmsConnection() {
    }

    public static ServerPlayer serverPlayer(Player player) {
        return ((CraftPlayer) player).getHandle();
    }

    public static Connection connection(Player player) {
        return serverPlayer(player).connection.connection;
    }

    public static Channel channel(Player player) {
        return connection(player).channel;
    }

    public static NativeConnection sender(Player player) {
        return packet -> serverPlayer(player).connection.send(cast(packet));
    }

    public static PacketRegistry.PacketRegistration registration(Channel channel, PacketContext packet) {
        Connection connection = channel.pipeline().get(Connection.class);
        if (connection == null || connection.getPacketListener() == null) {
            throw new IllegalStateException("NMS connection protocol is not initialized");
        }
        ConnectionProtocol protocol = connection.getPacketListener().protocol();
        PacketRegistry.Phase phase = switch (protocol) {
            case HANDSHAKING -> PacketRegistry.Phase.HANDSHAKE;
            case STATUS -> PacketRegistry.Phase.STATUS;
            case LOGIN -> PacketRegistry.Phase.LOGIN;
            case CONFIGURATION -> PacketRegistry.Phase.CONFIGURATION;
            case PLAY -> PacketRegistry.Phase.PLAY;
        };
        PacketRegistry.Direction direction = packet.direction() == dev.shamoo.runtime.platform.paper.packet
                .PaperPacketBridge.Direction.INBOUND ? PacketRegistry.Direction.SERVERBOUND
                        : PacketRegistry.Direction.CLIENTBOUND;
        return packet.packet().descriptor().registrations().stream()
                .filter(value -> value.phase() == phase && value.direction() == direction)
                .findFirst().orElseThrow(() -> new SecurityException("packet has no registration for current "
                        + phase + ' ' + direction));
    }

    private static Packet<?> cast(Object packet) {
        return (Packet<?>) packet;
    }
}
