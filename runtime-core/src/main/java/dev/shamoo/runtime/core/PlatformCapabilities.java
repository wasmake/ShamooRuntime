package dev.shamoo.runtime.core;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Immutable allowlist of narrow platform operations; platform service objects are never exposed. */
public final class PlatformCapabilities {
    public static final PlatformCapabilities NONE = new PlatformCapabilities("none", Map.of());
    private final String namespace;
    private final Map<String, Operation> bindings;
    private final Map<InvocationKey, InvocationAdapter> adapters = new ConcurrentHashMap<>();

    public PlatformCapabilities(Map<String, Operation> operations) {
        this("platform", operations);
    }

    public PlatformCapabilities(String namespace, Map<String, Operation> operations) {
        this.namespace = identifier(namespace, "namespace");
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

    public String bindingNamespace() {
        return namespace;
    }

    /** Resolves and caches only immutable generated identity; plugin owners and runtimes are invocation arguments. */
    public Object invoke(String operationName, PluginId owner, Map<?, ?> metadata, List<Object> arguments)
            throws Exception {
        return invoke(operationName, owner, CompiledBindingMetadata.from(metadata), arguments);
    }

    /** Invokes a capability using metadata that was already parsed by the authorization layer. */
    public Object invoke(String operationName, PluginId owner, CompiledBindingMetadata metadata,
            List<Object> arguments) throws Exception {
        Operation operation = bindings.get(operationName);
        if (operation == null) {
            throw new IllegalArgumentException("unknown platform capability: " + operationName);
        }
        Objects.requireNonNull(metadata, "metadata");
        if (!operationName.equals(metadata.typeName())) {
            throw new IllegalArgumentException("platform capability " + operationName
                    + " requires matching generated typeName");
        }
        if (!namespace.equals(metadata.namespace())) {
            throw new IllegalArgumentException("platform capability " + operationName
                    + " requires generated namespace " + namespace);
        }
        InvocationKey key = new InvocationKey(operationName, metadata);
        InvocationAdapter adapter = adapters.computeIfAbsent(key,
                ignored -> new InvocationAdapter(operation, metadata));
        return adapter.invoke(Objects.requireNonNull(owner, "owner"), arguments);
    }

    int cachedAdapterCount() {
        return adapters.size();
    }

    private static String identifier(String value, String name) {
        if (value == null || !value.matches("[A-Za-z_$][A-Za-z0-9_$.-]*")) {
            throw new IllegalArgumentException(name + " is not a generated binding identifier");
        }
        return value;
    }

    private record InvocationKey(String operationName, CompiledBindingMetadata metadata) {
    }

    private record InvocationAdapter(Operation operation, CompiledBindingMetadata metadata) {
        private Object invoke(PluginId owner, List<Object> arguments) throws Exception {
            return operation.invoke(owner, metadata, Objects.requireNonNull(arguments, "arguments"));
        }
    }

    @FunctionalInterface
    public interface Operation {
        Object invoke(PluginId owner, CompiledBindingMetadata metadata, List<Object> arguments) throws Exception;
    }
}
