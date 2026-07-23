package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Objects;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.plugin.java.JavaPlugin;

/** Folia-aware Paper scheduler adapter with explicit location/entity ownership. */
public final class PaperSchedulerBridge {
    private final JavaPlugin plugin;
    private final ResourceRegistry resources;

    public PaperSchedulerBridge(JavaPlugin plugin, ResourceRegistry resources) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public Task runAsync(PluginId owner, Runnable action) {
        ScheduledTask task = plugin.getServer().getAsyncScheduler().runNow(plugin, ignored -> action.run());
        return own(owner, "paper async task", task);
    }

    public Task runGlobal(PluginId owner, Runnable action) {
        ScheduledTask task = plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> action.run());
        return own(owner, "paper global task", task);
    }

    public Task runRegion(PluginId owner, Location location, Runnable action) {
        Objects.requireNonNull(location.getWorld(), "location world");
        ScheduledTask task = plugin.getServer().getRegionScheduler().run(plugin, location, ignored -> action.run());
        return own(owner, "paper region task", task);
    }

    public Task runEntity(PluginId owner, Entity entity, Runnable action, Runnable retired) {
        ScheduledTask task = entity.getScheduler().run(plugin, ignored -> action.run(), retired);
        if (task == null) {
            throw new IllegalStateException("entity is retired and cannot own a scheduled task");
        }
        return own(owner, "paper entity task", task);
    }

    public boolean ownsCurrentRegion(Location location) {
        return plugin.getServer().isOwnedByCurrentRegion(location);
    }

    public boolean ownsCurrentRegion(Entity entity) {
        return plugin.getServer().isOwnedByCurrentRegion(entity);
    }

    private Task own(PluginId owner, String description, ScheduledTask task) {
        return resources.register(owner, ResourceCategory.TASK, description, new Task(task));
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
