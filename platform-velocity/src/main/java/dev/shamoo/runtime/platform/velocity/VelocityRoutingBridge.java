package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Collection;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

/** Controlled connection/backend routing using Velocity's public API. */
public final class VelocityRoutingBridge {
    private final ProxyServer server;

    public VelocityRoutingBridge(ProxyServer server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public Optional<RegisteredServer> backend(String name) {
        return server.getServer(name);
    }

    public Collection<RegisteredServer> backends() {
        return ListCopy.copy(server.getAllServers());
    }

    public CompletableFuture<ConnectionRequestBuilder.Result> connect(Player player, RegisteredServer backend) {
        return player.createConnectionRequest(backend).connect();
    }

    private static final class ListCopy {
        private ListCopy() {
        }

        static <T> Collection<T> copy(Collection<T> values) {
            return java.util.List.copyOf(values);
        }
    }
}
