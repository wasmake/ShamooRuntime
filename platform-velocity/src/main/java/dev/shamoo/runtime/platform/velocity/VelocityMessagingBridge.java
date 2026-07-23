package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.RegistrationRefCounter;
import java.util.Objects;

/** Velocity channel registration and lossless payload sender. */
public final class VelocityMessagingBridge {
    private final ProxyServer server;
    private final ResourceRegistry resources;
    private final RegistrationRefCounter<ChannelIdentifier> references = new RegistrationRefCounter<>();

    public VelocityMessagingBridge(ProxyServer server, ResourceRegistry resources) {
        this.server = Objects.requireNonNull(server, "server");
        this.resources = Objects.requireNonNull(resources, "resources");
    }

    public AutoCloseable register(PluginId owner, ChannelIdentifier identifier) {
        AutoCloseable reference = references.acquire(identifier,
                () -> server.getChannelRegistrar().register(identifier),
                () -> server.getChannelRegistrar().unregister(identifier));
        return resources.register(owner, ResourceCategory.MESSAGE, identifier.getId(),
                reference);
    }

    public boolean send(ChannelMessageSink sink, ChannelIdentifier identifier, byte[] payload) {
        return sink.sendPluginMessage(identifier, payload.clone());
    }
}
