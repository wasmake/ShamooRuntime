package dev.shamoo.runtime.protocol;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Negotiates descriptor requirements against a concrete host without platform implementation types. */
public final class CompatibilityNegotiator {
    public CompatibilityResult negotiate(PluginDescriptor descriptor, CompatibilityInput runtime) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(runtime, "runtime");
        List<IncompatibilityReason> reasons = new ArrayList<>();

        if (!runtime.protocolVersion().isCompatibleWith(ProtocolVersion.CURRENT)) {
            reasons.add(reason("protocol_version", "Required plugin manifest protocol "
                    + ProtocolVersion.CURRENT + " is incompatible with runtime protocol "
                    + runtime.protocolVersion()));
        }
        if (!descriptor.shamoo().runtime().includes(runtime.runtimeVersion())) {
            reasons.add(reason("runtime_version", "Runtime " + runtime.runtimeVersion().value()
                    + " does not satisfy " + descriptor.shamoo().runtime().value()));
        }
        if (!descriptor.shamoo().api().includes(runtime.apiVersion())) {
            reasons.add(reason("api_version", "API " + runtime.apiVersion().value()
                    + " does not satisfy " + descriptor.shamoo().api().value()));
        }

        checkPlatform(descriptor.platforms(), runtime, reasons);

        Set<RuntimeCapability> requested = requestedCapabilities(descriptor.node());
        requested.removeAll(runtime.capabilities());
        for (RuntimeCapability missing : requested) {
            reasons.add(reason("missing_capability", "Runtime does not provide requested capability " + missing));
        }
        return new CompatibilityResult(reasons.isEmpty(), reasons);
    }

    private static void checkPlatform(
            PlatformTargets platforms, CompatibilityInput runtime, List<IncompatibilityReason> reasons) {
        if (runtime.platform() == PlatformKind.PAPER) {
            PaperTarget paper = platforms.paper();
            if (!paper.enabled()) {
                reasons.add(reason("platform_target", "Plugin does not enable the paper target"));
                return;
            }
            addVersionMismatch(
                    reasons, "minecraft_version", "Minecraft", runtime.minecraftVersion(), paper.minecraft());
            addVersionMismatch(reasons, "paper_api_version", "Paper API", runtime.paperApiVersion(), paper.paperApi());
        } else {
            VelocityTarget velocity = platforms.velocity();
            if (!velocity.enabled()) {
                reasons.add(reason("platform_target", "Plugin does not enable the velocity target"));
                return;
            }
            addVersionMismatch(reasons, "velocity_api_version", "Velocity API",
                    runtime.velocityApiVersion(), velocity.velocityApi());
        }
    }

    private static void addVersionMismatch(
            List<IncompatibilityReason> reasons,
            String code,
            String label,
            SemanticVersion actual,
            SemverRange requested) {
        if (!requested.includes(actual)) {
            reasons.add(reason(code, label + " " + actual.value() + " does not satisfy " + requested.value()));
        }
    }

    private static Set<RuntimeCapability> requestedCapabilities(NodePolicy node) {
        Set<RuntimeCapability> requested = EnumSet.noneOf(RuntimeCapability.class);
        if (!node.builtins().isEmpty()) {
            requested.add(RuntimeCapability.NODE_BUILTINS);
        }
        if (!node.filesystem().read().isEmpty()) {
            requested.add(RuntimeCapability.FILESYSTEM_READ);
        }
        if (!node.filesystem().write().isEmpty()) {
            requested.add(RuntimeCapability.FILESYSTEM_WRITE);
        }
        addIf(requested, RuntimeCapability.NETWORK, node.network());
        addIf(requested, RuntimeCapability.WORKERS, node.workers());
        addIf(requested, RuntimeCapability.CHILD_PROCESS, node.childProcess());
        addIf(requested, RuntimeCapability.NATIVE_ADDONS, node.nativeAddons());
        return requested;
    }

    private static void addIf(Set<RuntimeCapability> capabilities, RuntimeCapability capability, boolean requested) {
        if (requested) {
            capabilities.add(capability);
        }
    }

    private static IncompatibilityReason reason(String code, String message) {
        return new IncompatibilityReason(code, message);
    }
}
