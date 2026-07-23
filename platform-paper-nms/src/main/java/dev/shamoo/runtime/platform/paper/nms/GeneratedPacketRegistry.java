package dev.shamoo.runtime.platform.paper.nms;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.Direction;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.PacketDescriptor;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.PacketRegistration;
import dev.shamoo.runtime.platform.paper.packet.PacketRegistry.Phase;
import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Links the generated packet model to classes from the exact mapped server. */
public final class GeneratedPacketRegistry {
    private static final String RESOURCE = "dev/shamoo/runtime/generated/paper-packets/model.json";
    private static final String API_VERSION = "1.21.8+paper.55+mache.2";

    private GeneratedPacketRegistry() {
    }

    public static PacketRegistry load(ClassLoader loader) throws IOException {
        try (InputStream input = loader.getResourceAsStream(RESOURCE)) {
            if (input == null) {
                throw new IOException("generated packet model is missing: " + RESOURCE);
            }
            byte[] model = input.readAllBytes();
            if (!PaperNmsCompatibility.PACKET_MODEL_SHA256.equals(java.util.HexFormat.of().formatHex(sha256(model)))) {
                throw new IOException("generated packet model checksum does not match the pinned NMS build");
            }
            JsonNode root = new ObjectMapper().readTree(model);
            String apiVersion = root.required("apiVersion").textValue();
            if (root.required("schemaVersion").intValue() != 2
                    || !"paper-packets".equals(root.required("platform").textValue())
                    || !API_VERSION.equals(apiVersion)) {
                throw new IOException("generated packet model is incompatible with this NMS adapter");
            }
            List<PacketDescriptor> descriptors = new ArrayList<>();
            for (JsonNode packet : root.required("packets")) {
                String javaName = packet.required("javaName").textValue();
                try {
                    Class<?> packetClass = Class.forName(javaName, false, loader);
                    List<PacketRegistration> registrations = new ArrayList<>();
                    for (JsonNode registration : packet.required("registrations")) {
                        Direction direction = Direction.valueOf(registration.required("direction").textValue()
                                .toUpperCase(Locale.ROOT));
                        Phase phase = Phase.valueOf(registration.required("phase").textValue()
                                .toUpperCase(Locale.ROOT));
                        Integer protocolId = registration.has("id") ? registration.required("id").intValue() : null;
                        registrations.add(new PacketRegistration(phase, direction, protocolId));
                    }
                    descriptors.add(new PacketDescriptor(PaperNmsCompatibility.MINECRAFT_VERSION + ':' + javaName,
                            packetClass, registrations));
                } catch (ClassNotFoundException | IllegalArgumentException exception) {
                    throw new IOException("generated packet cannot be linked: " + javaName, exception);
                }
            }
            return new PacketRegistry(PaperNmsCompatibility.MINECRAFT_VERSION, descriptors);
        }
    }

    private static byte[] sha256(byte[] value) throws IOException {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value);
        } catch (NoSuchAlgorithmException exception) {
            throw new IOException("SHA-256 is unavailable", exception);
        }
    }
}
