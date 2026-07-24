package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.OptionalProxyTransport;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.RegistrationRefCounter;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.PluginMessageListener;

/** Bukkit plugin messaging adapter preserving raw payload bytes. */
@SuppressWarnings({"PMD.CloseResource", "PMD.NullAssignment"})
public final class PaperMessagingBridge {
    public static final String PROXY_CHANNEL = "shamoo:runtime_v1";
    private final JavaPlugin plugin;
    private final ResourceRegistry resources;
    private final RegistrationRefCounter<String> outgoingReferences = new RegistrationRefCounter<>();
    private volatile java.util.UUID proxyCarrierId;

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

    /** Registers the response channel without making Velocity a standalone Paper requirement. */
    public AutoCloseable registerProxyTransport(PluginId owner, OptionalProxyTransport transport) {
        Objects.requireNonNull(transport, "transport");
        return register(owner, PROXY_CHANNEL, (channel, player, payload) -> {
            java.util.UUID expectedCarrier = proxyCarrierId;
            if (PROXY_CHANNEL.equals(channel) && expectedCarrier != null) {
                transport.receive(payload, player.isOnline() && expectedCarrier.equals(player.getUniqueId()));
            }
        });
    }

    /** Selects an online player as the optional Bukkit plugin-message carrier. */
    public boolean carrier(Player player, OptionalProxyTransport transport) {
        Objects.requireNonNull(player, "player");
        Objects.requireNonNull(transport, "transport");
        if (!player.isOnline() || !player.getListeningPluginChannels().contains(PROXY_CHANNEL)) {
            proxyCarrierId = null;
            transport.clearCarrier();
            return false;
        }
        proxyCarrierId = player.getUniqueId();
        transport.carrier(payload -> {
            if (!player.isOnline() || !player.getUniqueId().equals(proxyCarrierId)
                    || !player.getListeningPluginChannels().contains(PROXY_CHANNEL)) {
                proxyCarrierId = null;
                transport.clearCarrier();
                return false;
            }
            send(player, PROXY_CHANNEL, payload);
            return true;
        });
        return true;
    }

    /** Selects the first legal online carrier without exposing a live Player across the script boundary. */
    public boolean selectCarrier(OptionalProxyTransport transport) {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            if (player.getListeningPluginChannels().contains(PROXY_CHANNEL) && carrier(player, transport)) {
                return true;
            }
        }
        proxyCarrierId = null;
        transport.clearCarrier();
        return false;
    }
}
