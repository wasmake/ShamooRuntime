package dev.shamoo.runtime.platform.paper.packet;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Exact-version generated packet allowlist; no runtime class lookup is exposed. */
public final class PacketRegistry {
    private final String exactVersion;
    private final Map<Class<?>, PacketDescriptor> byClass;
    private final Map<String, PacketDescriptor> byId;
    private final Map<RegistrationKey, PacketDescriptor> byRegistration;

    public PacketRegistry(String minecraftVersion, Collection<PacketDescriptor> descriptors) {
        this.exactVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        byClass = descriptors.stream().collect(Collectors.toUnmodifiableMap(PacketDescriptor::packetClass,
                Function.identity()));
        byId = descriptors.stream().collect(Collectors.toUnmodifiableMap(PacketDescriptor::id, Function.identity()));
        byRegistration = descriptors.stream().flatMap(descriptor -> descriptor.registrations().stream()
                .filter(registration -> registration.protocolId() != null)
                .map(registration -> Map.entry(new RegistrationKey(registration.phase(), registration.direction(),
                        registration.protocolId()), descriptor)))
                .collect(Collectors.toUnmodifiableMap(Map.Entry::getKey, Map.Entry::getValue));
        if (byId.values().stream().anyMatch(value -> !value.id().startsWith(minecraftVersion + ':'))) {
            throw new IllegalArgumentException("packet descriptor does not match registry version " + minecraftVersion);
        }
    }

    PacketHandle wrap(Object packet) {
        PacketDescriptor descriptor = byClass.get(packet.getClass());
        if (descriptor == null) {
            throw new SecurityException("packet type is outside the generated NMS allowlist");
        }
        return new PacketHandle(descriptor, packet);
    }

    Object unwrap(PacketHandle handle) {
        PacketDescriptor expected = byId.get(handle.descriptor().id());
        if (expected == null || expected.packetClass() != handle.value().getClass()) {
            throw new SecurityException("packet handle is outside the generated NMS allowlist");
        }
        return handle.value();
    }

    Object unwrapReplacement(PacketHandle original, PacketHandle replacement, boolean allowCrossRegistration) {
        return unwrapReplacement(replacement, original.descriptor().registrations().getFirst(),
                allowCrossRegistration);
    }

    Object unwrapReplacement(PacketHandle replacement, PacketRegistration current, boolean allowCrossRegistration) {
        Object value = unwrap(replacement);
        if (!allowCrossRegistration && !replacement.descriptor().registrations().contains(current)) {
            throw new SecurityException("packet replacement is not registered as " + current);
        }
        return value;
    }

    PacketRegistration requireRegistration(PacketHandle packet, Phase phase, Direction direction) {
        return packet.descriptor().registrations().stream()
                .filter(value -> value.phase() == phase && value.direction() == direction)
                .findFirst().orElseThrow(() -> new SecurityException("packet is not registered for "
                        + phase + ' ' + direction));
    }

    public Optional<PacketDescriptor> registration(Phase phase, Direction direction, int protocolId) {
        if (protocolId < 0) {
            throw new IllegalArgumentException("packet protocol id must be nonnegative");
        }
        return Optional.ofNullable(byRegistration.get(new RegistrationKey(phase, direction, protocolId)));
    }

    public String minecraftVersion() {
        return exactVersion;
    }

    public record PacketDescriptor(String id, Class<?> packetClass, List<PacketRegistration> registrations) {
        public PacketDescriptor {
            Objects.requireNonNull(id, "id");
            Objects.requireNonNull(packetClass, "packetClass");
            registrations = registrations.stream().distinct().sorted().toList();
            if (registrations.isEmpty()) {
                throw new IllegalArgumentException("packet descriptor must have a registration");
            }
        }

        @Override
        public List<PacketRegistration> registrations() {
            return List.copyOf(registrations);
        }
    }

    @SuppressWarnings("PMD.OverrideBothEqualsAndHashCodeOnComparable")
    public record PacketRegistration(Phase phase, Direction direction, Integer protocolId)
            implements Comparable<PacketRegistration> {
        public PacketRegistration {
            Objects.requireNonNull(phase, "phase");
            Objects.requireNonNull(direction, "direction");
            if (protocolId != null && protocolId < 0) {
                throw new IllegalArgumentException("packet protocol id must be nonnegative");
            }
        }

        @Override
        public int compareTo(PacketRegistration other) {
            int result = phase.compareTo(other.phase);
            result = result == 0 ? direction.compareTo(other.direction) : result;
            return result == 0 ? java.util.Comparator.nullsLast(Integer::compareTo)
                    .compare(protocolId, other.protocolId) : result;
        }
    }

    private record RegistrationKey(Phase phase, Direction direction, int protocolId) {
    }

    public enum Direction { CLIENTBOUND, SERVERBOUND }

    public enum Phase { HANDSHAKE, STATUS, LOGIN, CONFIGURATION, PLAY, COMMON }
}
