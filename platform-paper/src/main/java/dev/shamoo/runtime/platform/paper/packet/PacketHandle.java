package dev.shamoo.runtime.platform.paper.packet;

import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.PacketDescriptor;
import java.util.Objects;

/** Opaque live packet handle; the underlying NMS object is never exposed to plugins. */
public final class PacketHandle {
    private final PacketDescriptor packetDescriptor;
    private final Object livePacket;

    PacketHandle(PacketDescriptor descriptor, Object value) {
        this.packetDescriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.livePacket = Objects.requireNonNull(value, "value");
    }

    public PacketDescriptor descriptor() {
        return packetDescriptor;
    }

    Object value() {
        return livePacket;
    }
}
