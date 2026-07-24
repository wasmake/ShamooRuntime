package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ServerConnection;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.RegistrationRefCounter;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import dev.shamoo.runtime.protocol.ProxyMessageCodec;
import dev.shamoo.runtime.protocol.ProxyMessageEnvelope;
import dev.shamoo.runtime.protocol.ProxyMessageType;

/** Velocity channel registration and lossless payload sender. */
@SuppressWarnings("PMD.CloseResource")
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

    /**
     * Registers a backend-only request endpoint. Unrelated, malformed, response, and untrusted-source messages retain
     * Velocity's forwarding result; only an accepted Shamoo request is marked handled.
     */
    public AutoCloseable registerProxyEndpoint(PluginId owner, Object plugin, ChannelIdentifier identifier,
            ProxyRequestHandler handler) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(handler, "handler");
        AutoCloseable channel = references.acquire(identifier,
                () -> server.getChannelRegistrar().register(identifier),
                () -> server.getChannelRegistrar().unregister(identifier));
        ProxyMessageCodec codec = new ProxyMessageCodec();
        EventHandler<PluginMessageEvent> listener = event -> {
            if (!identifier.equals(event.getIdentifier()) || !(event.getSource() instanceof ServerConnection source)) {
                return;
            }
            ProxyMessageEnvelope request;
            try {
                request = codec.decode(event.getData());
            } catch (IllegalArgumentException exception) {
                return;
            }
            if (request.type() != ProxyMessageType.REQUEST) {
                return;
            }
            event.setResult(PluginMessageEvent.ForwardResult.handled());
            CompletionStage<byte[]> response;
            try {
                response = Objects.requireNonNull(handler.handle(event.getData().clone()),
                        "proxy request handler returned null");
            } catch (RuntimeException exception) {
                response = CompletableFuture.failedFuture(exception);
            }
            response.handle((encoded, failure) -> {
                byte[] outgoing;
                if (failure != null) {
                    outgoing = codec.encode(ProxyMessageEnvelope.error(request.requestId(), "request_failed",
                            "The remote request failed."));
                } else {
                    try {
                        ProxyMessageEnvelope decoded = codec.decode(Objects.requireNonNull(encoded,
                                "proxy request response was null"));
                        if (decoded.type() == ProxyMessageType.REQUEST
                                || !decoded.requestId().equals(request.requestId())) {
                            throw new IllegalArgumentException("proxy response does not match request");
                        }
                        outgoing = encoded.clone();
                    } catch (RuntimeException exception) {
                        outgoing = codec.encode(ProxyMessageEnvelope.error(request.requestId(), "invalid_response",
                                "The remote endpoint returned an invalid response."));
                    }
                }
                source.sendPluginMessage(identifier, outgoing);
                return null;
            });
        };
        server.getEventManager().register(plugin, PluginMessageEvent.class, listener);
        return resources.register(owner, ResourceCategory.MESSAGE, identifier.getId(), () -> {
            server.getEventManager().unregister(plugin, listener);
            channel.close();
        });
    }

    @FunctionalInterface
    public interface ProxyRequestHandler {
        CompletionStage<byte[]> handle(byte[] requestEnvelope);
    }
}
