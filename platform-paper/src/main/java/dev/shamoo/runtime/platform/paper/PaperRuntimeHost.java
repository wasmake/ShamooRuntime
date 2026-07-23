package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.RuntimeHost;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper scheduler and logging adapter for the platform-neutral runtime. */
public final class PaperRuntimeHost implements RuntimeHost {
    private final JavaPlugin plugin;
    private final System.Logger systemLogger;

    public PaperRuntimeHost(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.systemLogger = System.getLogger(plugin.getClass().getName());
    }

    @Override
    public String platformName() {
        return "paper";
    }

    @Override
    public System.Logger logger() {
        return systemLogger;
    }

    @Override
    public CompletionStage<Void> dispatch(Runnable task) {
        Objects.requireNonNull(task, "task");
        CompletableFuture<Void> result = new CompletableFuture<>();
        plugin.getServer().getScheduler().runTask(plugin, () -> {
            try {
                task.run();
                result.complete(null);
            } catch (RuntimeException exception) {
                result.completeExceptionally(exception);
            }
        });
        return result;
    }
}
