package dev.shamoo.runtime.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.event.EventHandler;
import com.velocitypowered.api.event.EventManager;
import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.ChannelIdentifier;
import com.velocitypowered.api.proxy.messages.ChannelMessageSink;
import com.velocitypowered.api.proxy.messages.ChannelRegistrar;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.protocol.ProxyMessageCodec;
import dev.shamoo.runtime.protocol.ProxyMessageEnvelope;
import java.lang.reflect.Proxy;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.AvoidLiteralsInIfCondition"
})
class VelocityMessagingBridgeTest {
    @Test
    @SuppressWarnings("unchecked")
    void consumesOnlyValidatedBackendRequestsAndPreservesOtherForwarding() throws Exception {
        AtomicReference<EventHandler<PluginMessageEvent>> registered = new AtomicReference<>();
        EventManager events = proxy(EventManager.class, (object, method, arguments) -> {
            if ("register".equals(method.getName())) {
                for (Object argument : arguments) {
                    if (argument instanceof EventHandler<?> handler) {
                        registered.set((EventHandler<PluginMessageEvent>) handler);
                    }
                }
            }
            return defaultValue(method.getReturnType());
        });
        ChannelRegistrar channels = proxy(ChannelRegistrar.class,
                (object, method, arguments) -> defaultValue(method.getReturnType()));
        ProxyServer server = proxy(ProxyServer.class, (object, method, arguments) -> switch (method.getName()) {
            case "getEventManager" -> events;
            case "getChannelRegistrar" -> channels;
            default -> defaultValue(method.getReturnType());
        });
        VelocityMessagingBridge bridge = new VelocityMessagingBridge(server, new ResourceRegistry());
        ChannelIdentifier channel = MinecraftChannelIdentifier.from("shamoo:runtime_v1");
        ProxyMessageCodec codec = new ProxyMessageCodec();
        bridge.registerProxyEndpoint(new PluginId("owner"), new Object(), channel, payload -> {
            ProxyMessageEnvelope decoded = codec.decode(payload);
            return CompletableFuture.completedFuture(codec.encode(
                    ProxyMessageEnvelope.success(decoded.requestId(), decoded.payload())));
        });
        ServerConnection source = proxy(ServerConnection.class,
                (object, method, arguments) -> defaultValue(method.getReturnType()));
        ChannelMessageSink target = proxy(ChannelMessageSink.class,
                (object, method, arguments) -> defaultValue(method.getReturnType()));

        PluginMessageEvent malformed = new PluginMessageEvent(source, target, channel, new byte[] {1});
        boolean malformedForwarded = malformed.getResult().isAllowed();
        registered.get().execute(malformed);
        assertTrue(malformedForwarded && malformed.getResult().isAllowed());

        byte[] request = codec.encode(ProxyMessageEnvelope.request(UUID.randomUUID(), "example/status", "1.0.0",
                "status", new byte[0]));
        PluginMessageEvent accepted = new PluginMessageEvent(source, target, channel, request);
        registered.get().execute(accepted);
        assertFalse(accepted.getResult().isAllowed());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler handler) {
        return (T) Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type}, handler);
    }

    private static Object defaultValue(Class<?> type) {
        if (type == boolean.class) {
            return false;
        }
        if (type == int.class || type == short.class || type == byte.class || type == long.class) {
            return 0;
        }
        return null;
    }
}
