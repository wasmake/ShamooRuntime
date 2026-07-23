package dev.shamoo.runtime.protocol;

import java.util.Objects;
import java.util.Set;

/** Runtime facts used to decide whether a descriptor can be admitted. */
public record CompatibilityInput(
        PlatformKind platform,
        SemanticVersion minecraftVersion,
        SemanticVersion paperApiVersion,
        SemanticVersion velocityApiVersion,
        Set<RuntimeCapability> capabilities,
        SemanticVersion runtimeVersion,
        SemanticVersion apiVersion,
        ProtocolVersion protocolVersion) {
    public CompatibilityInput {
        platform = Objects.requireNonNull(platform, "platform");
        capabilities = Set.copyOf(Objects.requireNonNull(capabilities, "capabilities"));
        runtimeVersion = Objects.requireNonNull(runtimeVersion, "runtimeVersion");
        apiVersion = Objects.requireNonNull(apiVersion, "apiVersion");
        protocolVersion = Objects.requireNonNull(protocolVersion, "protocolVersion");
        if (platform == PlatformKind.PAPER) {
            minecraftVersion = Objects.requireNonNull(minecraftVersion, "minecraftVersion");
            paperApiVersion = Objects.requireNonNull(paperApiVersion, "paperApiVersion");
        } else {
            velocityApiVersion = Objects.requireNonNull(velocityApiVersion, "velocityApiVersion");
        }
    }
}
