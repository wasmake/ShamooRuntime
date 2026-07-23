package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.RegistrationRefCounter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Bukkit plugin messaging adapter preserving raw payload bytes. */
@SuppressWarnings("PMD.CloseResource")
public final class PaperMessagingBridge {
    private final JavaPlugin plugin;
    private final ResourceRegistry resources;
    private final RegistrationRefCounter<String> outgoingReferences = new RegistrationRefCounter<>();

    public PaperMessagingBridge(JavaPlugin plugin, ResourceRegistry resources) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public AutoCloseable register(PluginId owner, String channel, PluginMessageListener listener) {
        var messenger = plugin.getServer().getMessenger();
        messenger.registerIncomingPluginChannel(plugin, channel, listener);
        AutoCloseable outgoing = outgoingReferences.acquire(channel,
                () -> messenger.registerOutgoingPluginChannel(plugin, channel),
                () -> messenger.unregisterOutgoingPluginChannel(plugin, channel));
        AtomicBoolean closed = new AtomicBoolean();
        return resources.register(owner, ResourceCategory.MESSAGE, channel, () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            messenger.unregisterIncomingPluginChannel(plugin, channel, listener);
            outgoing.close();
        });
    }

    public void send(Player player, String channel, byte[] payload) {
        player.sendPluginMessage(plugin, channel, payload.clone());
    }
}
