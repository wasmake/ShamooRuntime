package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.protocol.NodePolicy;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Owns exactly one isolated Node runtime for each admitted plugin identity. */
@SuppressWarnings("PMD.CloseResource")
public final class ShamooNodeRuntimeManager implements AutoCloseable {
    private final Map<PluginId, ShamooNodeRuntime> runtimes = new LinkedHashMap<>();

    public synchronized ShamooNodeRuntime create(
            PluginId pluginId,
            Path pluginRoot,
            NodePolicy policy,
            Map<String, HostFunction> hostBindings,
            ShamooNodeRuntimeOptions options,
            RuntimeErrorReporter errorReporter) {
        Objects.requireNonNull(pluginId, "pluginId");
        if (runtimes.containsKey(pluginId)) {
            throw new IllegalStateException("runtime already exists for " + pluginId);
        }
        ShamooNodeRuntime runtime = ShamooNodeRuntime.create(
            pluginId, pluginRoot, policy, hostBindings, options, errorReporter);
        runtimes.put(pluginId, runtime);
        return runtime;
    }

    public synchronized ShamooNodeRuntime get(PluginId pluginId) {
        return runtimes.get(pluginId);
    }

    public synchronized void close(PluginId pluginId) {
        ShamooNodeRuntime runtime = runtimes.get(pluginId);
        if (runtime != null) {
            try {
                runtime.close();
            } finally {
                runtimes.remove(pluginId, runtime);
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
}
