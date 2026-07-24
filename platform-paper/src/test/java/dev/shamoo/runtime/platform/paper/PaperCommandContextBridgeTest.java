package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.PlayerInventory;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.UnitTestContainsTooManyAsserts"})
class PaperCommandContextBridgeTest {
    @Test
    void callbackContainsOnlyTokenSenderDtoAliasAndArguments() {
        UUID playerId = UUID.randomUUID();
        Player player = player("Alex", playerId, null);
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(null, null, new AtomicInteger()));

        assertTrue(bridge.execute(player, "sample", List.of("one", "two"), context -> {
            assertEquals(java.util.Set.of("token", "sender", "alias", "arguments"), context.keySet());
            assertTrue(context.get("token") instanceof String);
            assertEquals("sample", context.get("alias"));
            assertEquals(List.of("one", "two"), context.get("arguments"));
            assertEquals(Map.of("name", "Alex", "kind", "player", "id", playerId.toString()),
                    context.get("sender"));
            return CompletableFuture.completedFuture(true);
        }));
    }

    @Test
    void tokenExpiresAfterCallbackAndFeedbackIsPlain() {
        AtomicReference<String> message = new AtomicReference<>();
        CommandSender sender = proxy(CommandSender.class, (target, method, arguments) -> switch (method.getName()) {
            case "getName" -> "Console";
            case "sendPlainMessage" -> {
                message.set((String) arguments[0]);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(null, null, new AtomicInteger()));
        AtomicReference<String> token = new AtomicReference<>();

        assertTrue(bridge.execute(sender, "sample", List.of(), context -> {
            token.set((String) context.get("token"));
            assertEquals(Map.of("name", "Console", "kind", "other"), context.get("sender"));
            assertTrue(bridge.reply(token.get(), "plain feedback"));
            return CompletableFuture.completedFuture(true);
        }));

        assertEquals("plain feedback", message.get());
        assertFalse(bridge.reply(token.get(), "expired"));
        assertNull(bridge.findPlayer(token.get(), "Alex"));
    }

    @Test
    void playerLookupPrefersExactOnlineThenCachedKnownPlayer() {
        Player online = player("Online", UUID.randomUUID(), null);
        OfflinePlayer cached = offlinePlayer("Known", UUID.randomUUID(), false);
        AtomicInteger cachedLookups = new AtomicInteger();
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(online, cached, cachedLookups));
        CommandSender sender = sender("Console");

        assertTrue(bridge.execute(sender, "sample", List.of(), context -> {
            String token = (String) context.get("token");
            assertEquals(Map.of("id", online.getUniqueId().toString(), "name", "Online", "online", true),
                    bridge.findPlayer(token, "Online"));
            assertEquals(0, cachedLookups.get());
            assertEquals(Map.of("id", cached.getUniqueId().toString(), "name", "Known", "online", false),
                    bridge.findPlayer(token, "Known"));
            assertNull(bridge.findPlayer(token, "Missing"));
            return CompletableFuture.completedFuture(true);
        }));
    }

    @Test
    void mainHandInspectionAndExactRemovalRejectMismatches() {
        ItemStack item = mock(ItemStack.class);
        when(item.getType()).thenReturn(Material.DIAMOND);
        when(item.getAmount()).thenReturn(3);
        AtomicReference<ItemStack> hand = new AtomicReference<>(item);
        PlayerInventory inventory = inventory(hand);
        Player player = player("Alex", UUID.randomUUID(), inventory);
        PaperCommandContextBridge bridge = new PaperCommandContextBridge(server(null, null, new AtomicInteger()));

        assertTrue(bridge.execute(player, "sample", List.of(), context -> {
            String token = (String) context.get("token");
            assertEquals(Map.of("material", "DIAMOND", "amount", 3), bridge.mainHand(token));
            assertFalse(bridge.takeMainHand(token, "EMERALD", 3));
            assertFalse(bridge.takeMainHand(token, "DIAMOND", 2));
            assertFalse(bridge.takeMainHand(token, "AIR", 3));
            assertFalse(bridge.takeMainHand(token, "DIAMOND", 0));
            assertEquals(Material.DIAMOND, hand.get().getType());
            assertTrue(bridge.takeMainHand(token, "DIAMOND", 3));
            assertNull(hand.get());
            assertNull(bridge.mainHand(token));
            return CompletableFuture.completedFuture(true);
        }));

        assertFalse(bridge.takeMainHand("expired", "DIAMOND", 3));
    }

    private static Server server(Player online, OfflinePlayer cached, AtomicInteger cachedLookups) {
        return proxy(Server.class, (target, method, arguments) -> switch (method.getName()) {
            case "getPlayerExact" -> online != null && online.getName().equals(arguments[0]) ? online : null;
            case "getOfflinePlayerIfCached" -> {
                cachedLookups.incrementAndGet();
                yield cached != null && cached.getName().equals(arguments[0]) ? cached : null;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static CommandSender sender(String name) {
        return proxy(CommandSender.class, (target, method, arguments) -> "getName".equals(method.getName())
                ? name : defaultValue(method.getReturnType()));
    }

    private static OfflinePlayer offlinePlayer(String name, UUID id, boolean online) {
        return proxy(OfflinePlayer.class, (target, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> id;
            case "isOnline" -> online;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static Player player(String name, UUID id, PlayerInventory inventory) {
        return proxy(Player.class, (target, method, arguments) -> switch (method.getName()) {
            case "getName" -> name;
            case "getUniqueId" -> id;
            case "isOnline" -> true;
            case "getInventory" -> inventory;
            default -> defaultValue(method.getReturnType());
        });
    }

    private static PlayerInventory inventory(AtomicReference<ItemStack> hand) {
        return proxy(PlayerInventory.class, (target, method, arguments) -> switch (method.getName()) {
            case "getItemInMainHand" -> hand.get();
            case "setItemInMainHand" -> {
                hand.set((ItemStack) arguments[0]);
                yield null;
            }
            default -> defaultValue(method.getReturnType());
        });
    }

    private static <T> T proxy(Class<T> type, InvocationHandler handler) {
        return type.cast(Proxy.newProxyInstance(Thread.currentThread().getContextClassLoader(),
                new Class<?>[] {type}, handler));
    }

    private static Object defaultValue(Class<?> type) {
        if (!type.isPrimitive()) {
            return null;
        }
        if (type == boolean.class) {
            return false;
        }
        if (type == char.class) {
            return '\0';
        }
        return 0;
    }
}
