package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.scheduler.ScheduledTask;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.time.Duration;
import java.util.Objects;

/** Velocity asynchronous scheduler adapter with plugin resource ownership. */
public final class VelocitySchedulerBridge {
    private final ProxyServer server;
    private final Object plugin;
    private final ResourceRegistry resources;

    public VelocitySchedulerBridge(ProxyServer server, Object plugin, ResourceRegistry resources) {
        this.server = Objects.requireNonNull(server, "server");
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public Task run(PluginId owner, Runnable action) {
        return own(owner, server.getScheduler().buildTask(plugin, action).schedule());
    }

    public Task runLater(PluginId owner, Duration delay, Runnable action) {
        return own(owner, server.getScheduler().buildTask(plugin, action).delay(delay).schedule());
    }

    private Task own(PluginId owner, ScheduledTask task) {
        return resources.register(owner, ResourceCategory.TASK, "velocity async task", new Task(task));
    }

    public static final class Task implements AutoCloseable {
        private final ScheduledTask scheduledTask;

        private Task(ScheduledTask task) {
            this.scheduledTask = task;
        }

        @Override
        public void close() {
            scheduledTask.cancel();
        }
    }
}
