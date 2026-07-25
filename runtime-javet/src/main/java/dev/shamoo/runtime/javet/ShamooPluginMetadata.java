package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.CompiledBindingMetadata;
import dev.shamoo.runtime.protocol.CompilerMetadata;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.PluginDescriptor;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Runtime authorization view derived solely from an already parsed plugin descriptor. */
public record ShamooPluginMetadata(
        PlatformKind platform,
        Set<String> lifecycle,
        Set<String> invocations,
        Map<CompilerMetadata.MethodId, CompilerMetadata.MethodAuthorization> methods,
        Map<String, String> services,
        Map<String, String> events,
        Map<String, String> consumers,
        Map<String, String> consumerPolicies,
        boolean nms,
        boolean packets,
        Map<String, Object> data) {
    private static final String COMMAND = "command";

    public ShamooPluginMetadata {
        Objects.requireNonNull(platform, "platform");
        lifecycle = Set.copyOf(lifecycle);
        invocations = Set.copyOf(invocations);
        methods = Map.copyOf(methods);
        services = Map.copyOf(services);
        events = Map.copyOf(events);
        consumers = Map.copyOf(consumers);
        consumerPolicies = Map.copyOf(consumerPolicies);
        data = Map.copyOf(data);
    }

    public static ShamooPluginMetadata from(PluginDescriptor descriptor, PlatformKind platform) {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(platform, "platform");
        if (platform == PlatformKind.PAPER && !descriptor.platforms().paper().enabled()) {
            throw new IllegalArgumentException("plugin does not enable the Paper target");
        }
        if (platform == PlatformKind.VELOCITY && !descriptor.platforms().velocity().enabled()) {
            throw new IllegalArgumentException("plugin does not enable the Velocity target");
        }
        CompilerMetadata compiler = descriptor.compiler();
        boolean paper = platform == PlatformKind.PAPER;
        return new ShamooPluginMetadata(platform, compiler.lifecycle(), compiler.invocations(), compiler.methods(),
                compiler.services(), compiler.events(), compiler.consumers(), compiler.consumerPolicies(),
                paper && descriptor.platforms().paper().nms(),
                paper && descriptor.platforms().paper().packets(), compiler.data());
    }

    public boolean permitsPlatformOperation(String operation, CompiledBindingMetadata binding) {
        Objects.requireNonNull(operation, "operation");
        Objects.requireNonNull(binding, "binding");
        if (!operation.equals(binding.typeName())) {
            return false;
        }
        String invocation = OPERATION_INVOCATIONS.get(operation);
        boolean nmsOperation = operation.toLowerCase(java.util.Locale.ROOT).contains("nms");
        if (invocation == null && !nmsOperation) {
            return false;
        }
        CompilerMetadata.MethodAuthorization authorization = methods.get(
                new CompilerMetadata.MethodId(binding.componentId(), binding.method()));
        if (authorization == null || !("common".equals(authorization.platform())
                || platform.name().equalsIgnoreCase(authorization.platform()))) {
            return false;
        }
        if (invocation != null && !invocation.equals(authorization.invocation())) {
            return false;
        }
        if ("paperSubscribePacket".equals(operation) && !packets) {
            return false;
        }
        return !nmsOperation || nms;
    }

    private static final Map<String, String> OPERATION_INVOCATIONS = Map.ofEntries(
            Map.entry("paperSubscribeEvent", "event"),
            Map.entry("velocitySubscribeEvent", "event"),
            Map.entry("paperRegisterCommand", COMMAND),
            Map.entry("velocityRegisterCommand", COMMAND),
            Map.entry("paperCommandReply", COMMAND),
            Map.entry("paperCommandFindPlayer", COMMAND),
            Map.entry("paperCommandMainHand", COMMAND),
            Map.entry("paperCommandTakeMainHand", COMMAND),
            Map.entry("paperScheduleGlobal", "task"),
            Map.entry("velocitySchedule", "task"),
            Map.entry("paperSubscribePacket", "packet"));
}
