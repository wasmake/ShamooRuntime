package dev.shamoo.runtime.platform.paper.packet;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertSame;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.Direction;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.PacketDescriptor;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.PacketRegistration;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.Phase;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.UnitTestContainsTooManyAsserts"
})
class PacketSecurityTest {
    @Test
    void deniesPluginWithoutOperatorOptIn() {
        PluginId plugin = new PluginId("example");
        PacketAccessPolicy policy = new PacketAccessPolicy(false, Set.of(plugin));
        assertThrows(SecurityException.class, () -> policy.require(plugin), "disabled packet access must be denied");
    }

    @Test
    void rejectsObjectsOutsideGeneratedRegistry() {
        PacketDescriptor descriptor = new PacketDescriptor(
                "1.21.8:allowed", AllowedPacket.class,
                List.of(new PacketRegistration(Phase.PLAY, Direction.CLIENTBOUND, 0)));
        PacketRegistry registry = new PacketRegistry("1.21.8", List.of(descriptor));
        assertThrows(SecurityException.class, () -> registry.wrap(new Object()),
                "unregistered classes must never become packet handles");
    }

    @Test
    void replacementRequiresSharedPhaseAndDirectionUnlessExplicitlyAllowed() {
        PacketDescriptor play = descriptor("play", AllowedPacket.class, Phase.PLAY, Direction.CLIENTBOUND, 0);
        PacketDescriptor login = descriptor("login", OtherPacket.class, Phase.LOGIN, Direction.CLIENTBOUND, 0);
        PacketRegistry registry = new PacketRegistry("1.21.8", List.of(play, login));
        PacketHandle original = registry.wrap(new AllowedPacket());
        PacketHandle replacement = registry.wrap(new OtherPacket());

        assertThrows(SecurityException.class, () -> registry.unwrapReplacement(original, replacement, false));
        assertSame(replacement.value(), registry.unwrapReplacement(original, replacement, true));
        PacketRegistration current = new PacketRegistration(Phase.LOGIN, Direction.CLIENTBOUND, 0);
        assertSame(replacement.value(), registry.unwrapReplacement(replacement, current, false));
    }

    private static PacketDescriptor descriptor(String id, Class<?> type, Phase phase, Direction direction,
            int protocolId) {
        return new PacketDescriptor("1.21.8:" + id, type,
                List.of(new PacketRegistration(phase, direction, protocolId)));
    }

    private static final class AllowedPacket {
    }

    private static final class OtherPacket {
    }
}
