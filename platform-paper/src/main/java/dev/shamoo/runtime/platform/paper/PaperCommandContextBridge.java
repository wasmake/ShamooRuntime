package dev.shamoo.runtime.platform.paper;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;

/** Provides data-only access to a command sender while its script callback is active. */
@SuppressWarnings("PMD.ReturnEmptyCollectionRatherThanNull")
public final class PaperCommandContextBridge {
    private static final int TOKEN_BYTES = 32;
    private static final int MAX_TOKEN_LENGTH = 64;
    private static final int MAX_MESSAGE_LENGTH = 32_767;
    private static final int MAX_PLAYER_NAME_LENGTH = 64;
    private static final int MAX_MATERIAL_LENGTH = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final Server server;
    private final Map<String, CommandSender> activeSenders = new ConcurrentHashMap<>();

    public PaperCommandContextBridge(Server server) {
        this.server = Objects.requireNonNull(server, "server");
    }

    public boolean execute(CommandSender sender, String alias, List<String> arguments,
            Function<Map<String, Object>, CompletionStage<?>> callback) {
        Objects.requireNonNull(sender, "sender");
        Objects.requireNonNull(alias, "alias");
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(callback, "callback");
        String token = activate(sender);
        try {
            Map<String, Object> context = Map.of(
                    "token", token,
                    "sender", senderData(sender),
                    "alias", alias,
                    "arguments", List.copyOf(arguments));
            CompletionStage<?> completion = Objects.requireNonNull(callback.apply(context), "callback completion");
            return Boolean.TRUE.equals(completion.toCompletableFuture().join());
        } finally {
            activeSenders.remove(token, sender);
        }
    }

    public boolean reply(String token, String message) {
        CommandSender sender = activeSender(token);
        if (sender == null || !bounded(message, MAX_MESSAGE_LENGTH, true)) {
            return false;
        }
        sender.sendPlainMessage(message);
        return true;
    }

    public Map<String, Object> findPlayer(String token, String name) {
        if (activeSender(token) == null || !bounded(name, MAX_PLAYER_NAME_LENGTH, false)) {
            return null;
        }
        Player online = server.getPlayerExact(name);
        if (online != null) {
            return playerData(online);
        }
        OfflinePlayer cached = server.getOfflinePlayerIfCached(name);
        return cached == null || cached.getName() == null ? null : playerData(cached);
    }

    public Map<String, Object> mainHand(String token) {
        CommandSender sender = activeSender(token);
        if (!(sender instanceof Player player)) {
            return null;
        }
        PlayerInventory inventory = player.getInventory();
        synchronized (inventory) {
            ItemStack item = inventory.getItemInMainHand();
            return item == null || isAir(item.getType()) ? null : itemData(item);
        }
    }

    public boolean takeMainHand(String token, String expectedMaterial, int expectedAmount) {
        CommandSender sender = activeSender(token);
        Material material = material(expectedMaterial);
        if (!(sender instanceof Player player) || material == null || expectedAmount <= 0) {
            return false;
        }
        PlayerInventory inventory = player.getInventory();
        synchronized (inventory) {
            ItemStack item = inventory.getItemInMainHand();
            if (item == null || item.getType() != material || item.getAmount() != expectedAmount) {
                return false;
            }
            inventory.setItemInMainHand(null);
            return true;
        }
    }

    private String activate(CommandSender sender) {
        byte[] bytes = new byte[TOKEN_BYTES];
        String token;
        do {
            RANDOM.nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        } while (activeSenders.putIfAbsent(token, sender) != null);
        return token;
    }

    private CommandSender activeSender(String token) {
        return bounded(token, MAX_TOKEN_LENGTH, false) ? activeSenders.get(token) : null;
    }

    private static Map<String, Object> senderData(CommandSender sender) {
        if (sender instanceof Player player) {
            return Map.of("name", player.getName(), "kind", "player", "id", player.getUniqueId().toString());
        }
        return Map.of("name", sender.getName(), "kind", "other");
    }

    private static Map<String, Object> playerData(OfflinePlayer player) {
        return Map.of("id", player.getUniqueId().toString(), "name", player.getName(), "online", player.isOnline());
    }

    private static Map<String, Object> itemData(ItemStack item) {
        return Map.of("material", item.getType().name(), "amount", item.getAmount());
    }

    private static Material material(String name) {
        if (!bounded(name, MAX_MATERIAL_LENGTH, false)) {
            return null;
        }
        Material material = Material.getMaterial(name.toUpperCase(Locale.ROOT));
        return material == null || isAir(material) ? null : material;
    }

    private static boolean isAir(Material material) {
        return material == Material.AIR || material == Material.CAVE_AIR || material == Material.VOID_AIR;
    }

    private static boolean bounded(String value, int maximumLength, boolean allowEmpty) {
        return value != null && value.length() <= maximumLength && (allowEmpty || !value.isBlank());
    }
}
