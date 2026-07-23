package dev.shamoo.runtime.platform.paper.nms.codegen;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import net.minecraft.SharedConstants;
import net.minecraft.network.ProtocolInfo;
import net.minecraft.network.protocol.configuration.ConfigurationProtocols;
import net.minecraft.network.protocol.game.GameProtocols;
import net.minecraft.network.protocol.handshake.HandshakeProtocols;
import net.minecraft.network.protocol.login.LoginProtocols;
import net.minecraft.network.protocol.status.StatusProtocols;
import net.minecraft.server.Bootstrap;

/** 1.21.8-only extractor over the mapped protocol registration API. */
public final class PacketRegistrationExtractor {
    private static final int ARGUMENT_COUNT = 1;

    private PacketRegistrationExtractor() {
    }

    public static void main(String[] arguments) throws IOException {
        if (arguments.length != ARGUMENT_COUNT) {
            throw new IllegalArgumentException("usage: <registrations.tsv>");
        }
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
        List<Registration> registrations = new ArrayList<>();
        add(registrations, "handshake", HandshakeProtocols.SERVERBOUND_TEMPLATE.details());
        add(registrations, "status", StatusProtocols.SERVERBOUND_TEMPLATE.details());
        add(registrations, "status", StatusProtocols.CLIENTBOUND_TEMPLATE.details());
        add(registrations, "login", LoginProtocols.SERVERBOUND_TEMPLATE.details());
        add(registrations, "login", LoginProtocols.CLIENTBOUND_TEMPLATE.details());
        add(registrations, "configuration", ConfigurationProtocols.SERVERBOUND_TEMPLATE.details());
        add(registrations, "configuration", ConfigurationProtocols.CLIENTBOUND_TEMPLATE.details());
        add(registrations, "play", GameProtocols.SERVERBOUND_TEMPLATE.details());
        add(registrations, "play", GameProtocols.CLIENTBOUND_TEMPLATE.details());
        List<String> lines = registrations.stream().distinct().sorted().map(Registration::tsv).toList();
        Path output = Path.of(arguments[0]);
        Files.createDirectories(Objects.requireNonNull(output.getParent(), "output must have a parent"));
        Files.write(output, lines);
    }

    private static void add(List<Registration> output, String phase, ProtocolInfo.Details details) {
        details.listPackets((type, id) -> output.add(new Registration(
                phase, type.flow().id(), type.id().getPath(), id)));
    }

    @SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
    private record Registration(String phase, String direction, String type, int id)
            implements Comparable<Registration> {
        private Registration {
            if (id < 0) {
                throw new IllegalStateException("official protocol emitted a negative packet id");
            }
        }

        private String tsv() {
            return String.join("\t", phase, direction, type, Integer.toString(id));
        }

        @Override
        public int compareTo(Registration other) {
            return Comparator.comparing(Registration::phase)
                    .thenComparing(Registration::direction)
                    .thenComparingInt(Registration::id)
                    .thenComparing(Registration::type)
                    .compare(this, other);
        }
    }
}
