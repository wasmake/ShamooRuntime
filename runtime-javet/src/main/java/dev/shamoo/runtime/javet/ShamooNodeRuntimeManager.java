package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.protocol.NodePolicy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Owns isolated Node runtimes by plugin generation so active and staged candidates may coexist. */
@SuppressWarnings("PMD.CloseResource")
public final class ShamooNodeRuntimeManager implements AutoCloseable {
    private final Map<RuntimeKey, ShamooNodeRuntime> runtimes = new LinkedHashMap<>();

    public synchronized ShamooNodeRuntime create(
            PluginId pluginId,
            Path pluginRoot,
            NodePolicy policy,
            Map<String, HostFunction> hostBindings,
            ShamooNodeRuntimeOptions options,
            RuntimeErrorReporter errorReporter) {
        if (runtimes.keySet().stream().anyMatch(key -> key.pluginId().equals(pluginId))) {
            throw new IllegalStateException("runtime already exists for " + pluginId);
        }
        return create(pluginId, UUID.randomUUID(), pluginRoot, policy, hostBindings, options, errorReporter);
    }

    public synchronized ShamooNodeRuntime create(
            PluginId pluginId,
            UUID generationId,
            Path pluginRoot,
            NodePolicy policy,
            Map<String, HostFunction> hostBindings,
            ShamooNodeRuntimeOptions options,
            RuntimeErrorReporter errorReporter) {
        Objects.requireNonNull(pluginId, "pluginId");
        RuntimeKey key = new RuntimeKey(pluginId, generationId);
        if (runtimes.containsKey(key)) {
            throw new IllegalStateException("runtime already exists for " + key);
        }
        ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
            pluginId, pluginRoot, policy, hostBindings, options, errorReporter);
        runtimes.put(key, runtime);
        return runtime;
    }

    public synchronized ShamooNodeRuntime get(PluginId pluginId) {
        return runtimes.entrySet().stream().filter(entry -> entry.getKey().pluginId().equals(pluginId))
                .map(Map.Entry::getValue).findFirst().orElse(null);
    }

    public synchronized ShamooNodeRuntime get(PluginId pluginId, UUID generationId) {
        return runtimes.get(new RuntimeKey(pluginId, generationId));
    }

    public synchronized void close(PluginId pluginId) {
        RuntimeKey key = runtimes.keySet().stream().filter(candidate -> candidate.pluginId().equals(pluginId))
                .findFirst().orElse(null);
        if (key != null) {
            close(key.pluginId(), key.generationId());
        }
    }

    public synchronized void close(PluginId pluginId, UUID generationId) {
        RuntimeKey key = new RuntimeKey(pluginId, generationId);
        ShamooNodeRuntime runtime = runtimes.get(key);
        if (runtime != null) {
            try {
                runtime.close();
            } finally {
                runtimes.remove(key, runtime);
            }
        }
    }

    public synchronized int size() {
        return runtimes.size();
    }

    @Override
    public synchronized void close() {
        RuntimeException failure = null;
        for (ShamooNodeRuntime runtime : runtimes.values()) {
            try {
                runtime.close();
            } catch (RuntimeException exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        runtimes.clear();
        if (failure != null) {
            throw failure;
        }
    }

    private record RuntimeKey(PluginId pluginId, UUID generationId) {
        private RuntimeKey {
            Objects.requireNonNull(pluginId, "pluginId");
            Objects.requireNonNull(generationId, "generationId");
        }
    }
}
