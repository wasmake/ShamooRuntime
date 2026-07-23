package dev.shamoo.runtime.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Immutable allowlist of narrow platform operations; platform service objects are never exposed. */
public final class PlatformCapabilities {
    public static final PlatformCapabilities NONE = new PlatformCapabilities(Map.of());
    private final Map<String, Operation> bindings;

    public PlatformCapabilities(Map<String, Operation> operations) {
        operations.forEach((name, operation) -> {
            if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("invalid platform capability name: " + name);
            }
            Objects.requireNonNull(operation, "operation");
        });
        this.bindings = Map.copyOf(operations);
    }

    public Map<String, Operation> operations() {
        return bindings;
    }

    @FunctionalInterface
    public interface Operation {
        Object invoke(PluginId owner, CompiledBindingMetadata metadata, List<Object> arguments) throws Exception;
    }
}
