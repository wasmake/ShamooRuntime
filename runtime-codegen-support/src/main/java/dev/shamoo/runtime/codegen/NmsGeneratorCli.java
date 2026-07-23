package dev.shamoo.runtime.codegen;

import dev.shamoo.runtime.codegen.ApiDescriptorWriter.PacketInfo;
import dev.shamoo.runtime.codegen.ApiDescriptorWriter.PacketRegistration;
import dev.shamoo.runtime.codegen.ApiDescriptorWriter.PacketInventory;
import dev.shamoo.runtime.codegen.ApiModel.ApiType;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.security.MessageDigest;

/** Generates canonical exact-version models from paperweight's mapped development server. */
public final class NmsGeneratorCli {
    private static final int MINIMUM_ARGUMENTS = 5;
    private static final int REGISTRATION_FIELDS = 4;

    private NmsGeneratorCli() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < MINIMUM_ARGUMENTS) {
            throw new IllegalArgumentException(
                    "usage: <api-version> <nms-output> <packet-output> <registrations.tsv> <mapped-artifact>...");
        }
        String version = arguments[0];
        Path nmsOutput = Path.of(arguments[1]);
        Path registrationsFile = Path.of(arguments[3]);
        List<Path> artifacts = Arrays.stream(arguments, 4, arguments.length).map(Path::of).toList();
        AsmApiScanner scanner = new AsmApiScanner();
        ApiModel nms = scanner.scan("paper-nms", artifacts, name -> name.startsWith("net.minecraft.")
                || name.startsWith("org.bukkit.craftbukkit."));
        ApiDescriptorWriter writer = new ApiDescriptorWriter();
        writer.write(nms, version, "mojang+paperweight", nmsOutput, List.of());

        Map<String, String> packetClasses = packetClassesByProtocolType(nms);
        Map<String, List<PacketRegistration>> registrations = readRegistrations(registrationsFile, packetClasses);
        long expectedRegistrations = registrations.values().stream().mapToLong(List::size).sum();
        List<ApiType> packetTypes = nms.types().stream()
                .filter(type -> registrations.containsKey(type.name())).toList();
        ApiModel packets = new ApiModel("paper-packets", packetTypes);
        List<PacketInfo> packetInfo = new ArrayList<>();
        for (ApiType type : packetTypes) {
            packetInfo.add(new PacketInfo(packetType(type.name()), type.name(), registrations.get(type.name())));
        }
        writer.write(packets, version, "mojang+paperweight", Path.of(arguments[2]), List.of(),
                packetInfo.stream().sorted().toList(),
                new PacketInventory(registrations.size(), expectedRegistrations, expectedRegistrations));
        String compatibility = """
                {
                  "minecraftVersion": "1.21.8",
                  "paperBuild": 55,
                  "paperApi": "1.21.8-R0.1-20250906.215025-55",
                  "paperweight": "2.0.0-beta.21",
                  "mache": "1.21.8+build.2",
                  "mapping": "mojang",
                  "mappedServerSha256": "@mappedServer@",
                  "packetModelSha256": "@packetModel@",
                  "pipelineAnchor": "packet_handler"
                }
                """.replace("@mappedServer@", checksum(artifacts.getFirst()))
                .replace("@packetModel@", checksum(Path.of(arguments[2]).resolve("model.json")));
        Path generatedRoot = Objects.requireNonNull(nmsOutput.getParent(), "NMS output must have a parent");
        Files.writeString(generatedRoot.resolve("compatibility.json"), compatibility);
    }

    private static String checksum(Path path) throws java.io.IOException {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(Files.readAllBytes(path)));
        } catch (java.security.NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static Map<String, List<PacketRegistration>> readRegistrations(Path input,
            Map<String, String> packetClasses) throws java.io.IOException {
        Map<String, List<PacketRegistration>> result = new HashMap<>();
        for (String line : Files.readAllLines(input, StandardCharsets.UTF_8)) {
            if (line.isBlank() || line.startsWith("#")) {
                continue;
            }
            String[] fields = line.split("\\t", -1);
            if (fields.length != REGISTRATION_FIELDS) {
                throw new IllegalArgumentException("invalid packet registration: " + line);
            }
            String javaName = packetClasses.get(fields[1] + ':' + fields[2]);
            if (javaName == null) {
                throw new IllegalStateException("official packet type has no concrete class mapping: " + fields[1]
                        + ':' + fields[2]);
            }
            result.computeIfAbsent(javaName, ignored -> new ArrayList<>()).add(
                    new PacketRegistration(fields[0], fields[1], Integer.valueOf(fields[3])));
        }
        return result;
    }

    private static Map<String, String> packetClassesByProtocolType(ApiModel nms) {
        Map<String, String> result = new HashMap<>();
        String prefix = "net.minecraft.network.protocol.PacketType<";
        for (ApiType type : nms.types()) {
            if (!type.name().endsWith("PacketTypes")) {
                continue;
            }
            for (ApiModel.ApiField field : type.fields()) {
                if (!"Lnet/minecraft/network/protocol/PacketType;".equals(field.descriptor())
                        || field.signature() == null) {
                    continue;
                }
                String value = JvmSignatures.field(field.signature(), field.descriptor());
                if (!value.startsWith(prefix) || !value.endsWith(">")) {
                    continue;
                }
                String direction = field.name().startsWith("CLIENTBOUND_") ? "clientbound"
                        : field.name().startsWith("SERVERBOUND_") ? "serverbound" : null;
                if (direction == null && "net.minecraft.network.protocol.handshake.HandshakePacketTypes"
                        .equals(type.name()) && "CLIENT_INTENTION".equals(field.name())) {
                    direction = "serverbound";
                }
                if (direction == null) {
                    continue;
                }
                String path = "CLIENT_INTENTION".equals(field.name()) ? "intention"
                        : field.name().substring(direction.length() + 1).toLowerCase(java.util.Locale.ROOT);
                String javaName = value.substring(prefix.length(), value.length() - 1);
                String previous = result.put(direction + ':' + path, javaName);
                if (previous != null && !previous.equals(javaName)) {
                    throw new IllegalStateException("duplicate packet protocol type " + direction + ':' + path);
                }
            }
        }
        return result;
    }

    private static String packetType(String javaName) {
        String simple = javaName.substring(javaName.lastIndexOf('.') + 1).replace('$', '_');
        return simple.replaceAll("[^A-Za-z0-9_$]", "_");
    }

}
