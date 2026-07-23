package dev.shamoo.runtime.platform.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.Objects;
import net.kyori.adventure.audience.Audience;

/** Exposes Velocity's native Adventure audiences without serialization. */
public final class VelocityAudienceBridge {
    public Audience player(Player player) {
        return Objects.requireNonNull(player, "player");
    }

    public Audience proxy(ProxyServer server) {
        return Objects.requireNonNull(server, "server");
    }

    public Audience backend(RegisteredServer server) {
        return Objects.requireNonNull(server, "server");
    }
}
