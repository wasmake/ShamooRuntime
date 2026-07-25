package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UnitTestAssertionsShouldIncludeMessage",
        "PMD.UnitTestContainsTooManyAsserts"})
class PaperCommandRouteTest {
    @Test
    void parsesRequiredOptionalGreedyAndAliasedOptions() {
        Server server = mock(Server.class);
        PaperCommandRoute route = parse(server, route("send <target> [amount]",
                List.of(argument("target", "string", List.of()), argument("amount", "integer", List.of())),
                List.of(option("material", "material", List.of("m"), List.of(), true),
                        option("silent", "boolean", List.of("s"), List.of(), false))));

        PaperCommandRoute.Match match = route.match(List.of(
                "send", "Alex", "3", "--material=diamond", "-s"));

        assertEquals(Map.of("target", "Alex", "amount", 3), match.arguments());
        assertEquals(Map.of("material", "DIAMOND", "silent", true), match.options());
        assertNull(route.match(List.of("send", "Alex", "not-an-integer", "--material=diamond")));
        assertNull(route.match(List.of("send", "Alex")), "required option must be present");

        PaperCommandRoute greedy = parse(server, route("say <message...>",
                List.of(argument("message", "string", List.of())), List.of()));
        assertEquals(Map.of("message", "hello from Paper"),
                greedy.match(List.of("say", "hello", "from", "Paper")).arguments());
    }

    @Test
    void playerParserReturnsOnlyDataAndFailuresDoNotMatch() {
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        UUID id = UUID.randomUUID();
        when(server.getPlayerExact("Alex")).thenReturn(player);
        when(player.getName()).thenReturn("Alex");
        when(player.getUniqueId()).thenReturn(id);
        when(player.isOnline()).thenReturn(true);
        PaperCommandRoute route = parse(server, route("<target>",
                List.of(argument("target", "player", List.of("players"))), List.of()));

        assertEquals(Map.of("target", Map.of("id", id.toString(), "name", "Alex", "online", true)),
                route.match(List.of("Alex")).arguments());
        assertNull(route.match(List.of("Missing")));
    }

    @Test
    void suggestsLiteralsStaticMagicBooleanAndOptionLabels() {
        Server server = mock(Server.class);
        Player player = mock(Player.class);
        when(player.getName()).thenReturn("Alex");
        doReturn(Set.of(player)).when(server).getOnlinePlayers();
        CommandSender sender = mock(CommandSender.class);
        PaperCommandRoute route = parse(server, route("teleport <target> [mode]",
                List.of(argument("target", "string", List.of("players")),
                        argument("mode", "string", List.of("safe", "exact"))),
                List.of(option("force", "boolean", List.of("f"), List.of(), false))));

        assertTrue(route.suggest(sender, List.of()).contains("teleport"));
        assertEquals(List.of("Alex"), route.suggest(sender, List.of("teleport", "A")));
        assertEquals(List.of("safe"), route.suggest(sender, List.of("teleport", "Alex", "s")));
        assertEquals(List.of("--force"), route.suggest(sender, List.of("--f")));
        assertEquals(List.of("--force=false", "--force=true"),
                route.suggest(sender, List.of("--force=")));
    }

    @Test
    void literalRouteIsMoreSpecificThanParsedCatchAll() {
        Server server = mock(Server.class);
        PaperCommandRoute literal = parse(server, route("reload", List.of(), List.of()));
        PaperCommandRoute catchAll = parse(server, route("<value>",
                List.of(argument("value", "string", List.of())), List.of()));

        assertTrue(literal.specificity().compareTo(catchAll.specificity()) > 0);
    }

    @Test
    void endOfOptionsAllowsDashPrefixedPositionalsAndChangesCompletion() {
        Server server = mock(Server.class);
        CommandSender sender = mock(CommandSender.class);
        PaperCommandRoute greedy = parse(server, route("say <message...>",
                List.of(argument("message", "string", List.of())),
                List.of(option("silent", "boolean", List.of("s"), List.of(), false))));

        assertNull(greedy.match(List.of("say", "--unknown", "value")));
        assertEquals(Map.of("message", "--unknown value"),
                greedy.match(List.of("say", "--", "--unknown", "value")).arguments());
        assertTrue(greedy.suggest(sender, List.of("--")).contains("--"));
        assertEquals(List.of(), greedy.suggest(sender, List.of("say", "--", "--unknown")));
    }

    @Test
    void materialParserAcceptsEveryBukkitMaterial() {
        PaperCommandRoute route = parse(mock(Server.class), route("<material>",
                List.of(argument("material", "material", List.of())), List.of()));

        assertEquals(Map.of("material", "WATER"), route.match(List.of("water")).arguments());
        assertEquals(Map.of("material", "DIAMOND"), route.match(List.of("diamond")).arguments());
    }

    private static Map<String, Object> route(String syntax, List<Object> arguments, List<Object> options) {
        return Map.of("syntax", syntax, "description", "test", "permission", "", "sender", "any",
                "arguments", arguments, "options", options);
    }

    private static PaperCommandRoute parse(Server server, Object descriptor) {
        return PaperCommandRoute.parse(server, descriptor);
    }

    private static Map<String, Object> argument(String name, String parser, Object suggestions) {
        return Map.of("name", name, "parser", parser, "suggestions", suggestions);
    }

    private static Map<String, Object> option(
            String name, String parser, List<String> aliases, Object suggestions, boolean required) {
        return Map.of("name", name, "parser", parser, "aliases", aliases,
                "suggestions", suggestions, "required", required);
    }
}
