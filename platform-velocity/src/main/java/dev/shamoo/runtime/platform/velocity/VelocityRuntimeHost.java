package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import dev.shamoo.runtime.core.RuntimeHost;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Velocity scheduler and logging adapter for the platform-neutral runtime. */
public final class VelocityRuntimeHost implements RuntimeHost {
    private final ProxyServer server;
    private final Object plugin;
    private final System.Logger systemLogger;

    public VelocityRuntimeHost(ProxyServer server, Object plugin) {
        this.server = Objects.requireNonNull(server, "server");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.systemLogger = System.getLogger(plugin.getClass().getName());
    }

    @Override
    public String platformName() {
        return "velocity";
    }

    @Override
    public System.Logger logger() {
        return systemLogger;
    }

    @Override
    public CompletionStage<Void> dispatch(Runnable task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<Void> result = new CompletableFuture<>();
        server.getScheduler().buildTask(plugin, () -> {
            try {
                task.run();
                result.complete(null);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        }).schedule();
        return result;
    }
}
