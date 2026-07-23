package dev.shamoo.runtime.platform.paper;

import java.util.Objects;
import net.kyori.adventure.audience.Audience;
import org.bukkit.Server;
import org.bukkit.entity.Player;

/** Exposes Paper's native Adventure audiences without serialization. */
public final class PaperAudienceBridge {
    public Audience player(Player player) {
        return Objects.requireNonNull(player, "player");
    }

    public Audience server(Server server) {
        return Objects.requireNonNull(server, "server");
    }
}
