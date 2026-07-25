package dev.shamoo.runtime.platform.paper;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.bukkit.Material;
import org.bukkit.Server;
import org.bukkit.command.CommandSender;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.entity.Player;

/** One validated command syntax and its strict parser. */
@SuppressWarnings({
    "PMD.AssignmentInOperand",
    "PMD.AvoidDuplicateLiterals",
    "PMD.AvoidFieldNameMatchingMethodName",
    "PMD.AvoidReassigningLoopVariables",
    "PMD.LooseCoupling",
    "PMD.OverrideBothEqualsAndHashCodeOnComparable"
})
final class PaperCommandRoute {
    private static final Set<String> ROUTE_KEYS = Set.of(
            "syntax", "description", "permission", "sender", "arguments", "options");
    private static final Set<String> ARGUMENT_KEYS = Set.of("name", "parser", "suggestions");
    private static final Set<String> OPTION_KEYS = Set.of(
            "name", "parser", "aliases", "suggestions", "required");
    private static final Comparator<String> SUGGESTION_ORDER = String.CASE_INSENSITIVE_ORDER.thenComparing(
            Comparator.naturalOrder());

    private final Server server;
    private final String syntax;
    private final String description;
    private final String permission;
    private final SenderKind senderKind;
    private final List<SyntaxNode> nodes;
    private final List<OptionDefinition> options;
    private final Map<String, OptionDefinition> optionTokens;
    private final Specificity specificity;

    private PaperCommandRoute(Server server, String syntax, String description, String permission,
            SenderKind senderKind, List<SyntaxNode> nodes, List<OptionDefinition> options) {
        this.server = server;
        this.syntax = syntax;
        this.description = description;
        this.permission = permission;
        this.senderKind = senderKind;
        this.nodes = List.copyOf(nodes);
        this.options = List.copyOf(options);
        Map<String, OptionDefinition> tokens = new HashMap<>();
        for (OptionDefinition option : options) {
            duplicate(tokens.put("--" + option.name(), option), "option name " + option.name());
            for (String alias : option.aliases()) {
                duplicate(tokens.put("-" + alias, option), "option alias " + alias);
            }
        }
        optionTokens = Map.copyOf(tokens);
        specificity = calculateSpecificity(nodes, options, senderKind);
    }

    static PaperCommandRoute parse(Server server, Object descriptor) {
        Objects.requireNonNull(server, "server");
        Map<String, Object> route = PaperDataDescriptor.object(
                descriptor, "command route", ROUTE_KEYS, ROUTE_KEYS);
        String syntax = PaperDataDescriptor.text(route.get("syntax"), "command route.syntax", true).trim();
        String description = PaperDataDescriptor.text(
                route.get("description"), "command route.description", true);
        String permission = PaperDataDescriptor.text(route.get("permission"), "command route.permission", true);
        SenderKind sender = SenderKind.parse(PaperDataDescriptor.text(
                route.get("sender"), "command route.sender", false));

        Map<String, ArgumentDefinition> arguments = arguments(route.get("arguments"));
        List<SyntaxNode> nodes = syntax(syntax, arguments);
        List<OptionDefinition> options = options(route.get("options"));
        return new PaperCommandRoute(server, syntax, description, permission, sender, nodes, options);
    }

    Match match(List<String> input) {
        OptionScan scan = scanOptions(input, true);
        if (scan == null) {
            return null;
        }
        Map<String, Object> arguments = new LinkedHashMap<>();
        int position = 0;
        for (SyntaxNode node : nodes) {
            if (node instanceof LiteralNode literal) {
                if (position >= scan.positionals().size()
                        || !literal.value().equalsIgnoreCase(scan.positionals().get(position))) {
                    return null;
                }
                position++;
            } else if (node instanceof ArgumentNode argument) {
                if (argument.greedy()) {
                    if (position >= scan.positionals().size()) {
                        if (argument.required()) {
                            return null;
                        }
                        continue;
                    }
                    String joined = String.join(" ", scan.positionals().subList(position, scan.positionals().size()));
                    ParseValue parsed = argument.definition().parser().parse(server, joined);
                    if (!parsed.matched()) {
                        return null;
                    }
                    arguments.put(argument.definition().name(), parsed.value());
                    position = scan.positionals().size();
                } else if (position < scan.positionals().size()) {
                    ParseValue parsed = argument.definition().parser().parse(
                            server, scan.positionals().get(position));
                    if (!parsed.matched()) {
                        return null;
                    }
                    arguments.put(argument.definition().name(), parsed.value());
                    position++;
                } else if (argument.required()) {
                    return null;
                }
            }
        }
        if (position != scan.positionals().size()) {
            return null;
        }
        return new Match(Map.copyOf(arguments), Map.copyOf(scan.values()));
    }

    List<String> suggest(CommandSender sender, List<String> input) {
        if (!allows(sender) || !hasPermission(sender)) {
            return List.of();
        }
        List<String> tokens = input.isEmpty() ? List.of("") : input;
        String partial = tokens.getLast();
        List<String> completed = tokens.subList(0, tokens.size() - 1);
        CompletionScan scan = scanForCompletion(completed);
        if (scan == null) {
            return List.of();
        }
        LinkedHashSet<String> result = new LinkedHashSet<>();
        OptionValue optionValue = scan.optionsEnded() ? null : optionValue(partial, scan);
        if (optionValue != null) {
            suggestions(optionValue.option().suggestions(), optionValue.option().parser()).stream()
                    .filter(value -> startsWith(value, optionValue.filter()))
                    .map(value -> optionValue.prefix() + value)
                    .forEach(result::add);
            return sorted(result);
        }
        if (!scan.optionsEnded() && partial.startsWith("-")) {
            optionLabels(scan.used()).stream().filter(value -> startsWith(value, partial)).forEach(result::add);
            if (startsWith("--", partial)) {
                result.add("--");
            }
            return sorted(result);
        }
        if (!scan.optionsEnded() && partial.isEmpty()) {
            result.addAll(optionLabels(scan.used()));
            result.add("--");
        }

        int nodeIndex = 0;
        int positionalIndex = 0;
        while (nodeIndex < nodes.size() && positionalIndex < scan.positionals().size()) {
            SyntaxNode node = nodes.get(nodeIndex);
            String token = scan.positionals().get(positionalIndex);
            if (node instanceof LiteralNode literal) {
                if (!literal.value().equalsIgnoreCase(token)) {
                    return List.of();
                }
                nodeIndex++;
                positionalIndex++;
            } else if (node instanceof ArgumentNode argument) {
                if (!argument.definition().parser().parse(server, token).matched()) {
                    return List.of();
                }
                positionalIndex++;
                if (!argument.greedy()) {
                    nodeIndex++;
                }
            }
        }
        if (positionalIndex != scan.positionals().size() || nodeIndex >= nodes.size()) {
            return sorted(result);
        }
        SyntaxNode current = nodes.get(nodeIndex);
        Collection<String> values = current instanceof LiteralNode literal ? List.of(literal.value())
                : suggestions(((ArgumentNode) current).definition().suggestions(),
                        ((ArgumentNode) current).definition().parser());
        values.stream().filter(value -> startsWith(value, partial)).forEach(result::add);
        return sorted(result);
    }

    boolean allows(CommandSender sender) {
        return senderKind.allows(sender);
    }

    boolean hasPermission(CommandSender sender) {
        return permission.isEmpty() || sender.hasPermission(permission);
    }

    Specificity specificity() {
        return specificity;
    }

    String description() {
        return description;
    }

    String syntax() {
        return syntax;
    }

    private OptionScan scanOptions(List<String> input, boolean requireOptions) {
        List<String> positional = new ArrayList<>();
        Map<String, Object> values = new LinkedHashMap<>();
        boolean optionsEnded = false;
        for (int index = 0; index < input.size(); index++) {
            String token = input.get(index);
            if (!optionsEnded && "--".equals(token)) {
                optionsEnded = true;
                continue;
            }
            if (optionsEnded) {
                positional.add(token);
                continue;
            }
            OptionToken optionToken = optionToken(token);
            if (optionToken == null) {
                if (token.startsWith("--")) {
                    return null;
                }
                positional.add(token);
                continue;
            }
            OptionDefinition option = optionToken.option();
            if (values.containsKey(option.name())) {
                return null;
            }
            String raw = optionToken.value();
            if (option.parser() == Parser.BOOLEAN && raw == null) {
                values.put(option.name(), true);
                continue;
            }
            if (raw == null) {
                if (++index >= input.size()) {
                    return null;
                }
                raw = input.get(index);
            }
            ParseValue parsed = option.parser().parse(server, raw);
            if (!parsed.matched()) {
                return null;
            }
            values.put(option.name(), parsed.value());
        }
        if (requireOptions && options.stream().anyMatch(option -> option.required()
                && !values.containsKey(option.name()))) {
            return null;
        }
        return new OptionScan(List.copyOf(positional), Map.copyOf(values));
    }

    private CompletionScan scanForCompletion(List<String> input) {
        List<String> positional = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        OptionDefinition awaiting = null;
        boolean optionsEnded = false;
        for (int index = 0; index < input.size(); index++) {
            String token = input.get(index);
            if (!optionsEnded && "--".equals(token)) {
                optionsEnded = true;
                continue;
            }
            if (optionsEnded) {
                positional.add(token);
                continue;
            }
            OptionToken optionToken = optionToken(token);
            if (optionToken == null) {
                if (token.startsWith("--")) {
                    return null;
                }
                positional.add(token);
                continue;
            }
            OptionDefinition option = optionToken.option();
            if (!used.add(option.name())) {
                return null;
            }
            if (option.parser() == Parser.BOOLEAN && optionToken.value() == null) {
                continue;
            }
            if (optionToken.value() == null) {
                if (++index >= input.size()) {
                    awaiting = option;
                    break;
                }
                if (!option.parser().parse(server, input.get(index)).matched()) {
                    return null;
                }
            } else if (!option.parser().parse(server, optionToken.value()).matched()) {
                return null;
            }
        }
        return new CompletionScan(List.copyOf(positional), Set.copyOf(used), awaiting, optionsEnded);
    }

    private OptionValue optionValue(String partial, CompletionScan scan) {
        if (scan.awaiting() != null) {
            return new OptionValue(scan.awaiting(), "", partial);
        }
        int equals = partial.indexOf('=');
        if (equals <= 0) {
            return null;
        }
        OptionDefinition option = optionTokens.get(partial.substring(0, equals));
        return option == null ? null : new OptionValue(
                option, partial.substring(0, equals + 1), partial.substring(equals + 1));
    }

    private OptionToken optionToken(String token) {
        int equals = token.indexOf('=');
        String label = equals < 0 ? token : token.substring(0, equals);
        OptionDefinition option = optionTokens.get(label);
        return option == null ? null : new OptionToken(option, equals < 0 ? null : token.substring(equals + 1));
    }

    private List<String> optionLabels(Set<String> used) {
        List<String> result = new ArrayList<>();
        for (OptionDefinition option : options) {
            if (!used.contains(option.name())) {
                result.add("--" + option.name());
                option.aliases().forEach(alias -> result.add("-" + alias));
            }
        }
        return result;
    }

    private Collection<String> suggestions(SuggestionSource source, Parser parser) {
        return switch (source.kind()) {
            case STATIC -> source.values();
            case PLAYERS -> server.getOnlinePlayers().stream().map(Player::getName).toList();
            case MATERIALS -> java.util.Arrays.stream(Material.values()).map(Material::name).toList();
            case NONE -> switch (parser) {
                case BOOLEAN -> List.of("true", "false");
                case PLAYER -> server.getOnlinePlayers().stream().map(Player::getName).toList();
                case MATERIAL -> java.util.Arrays.stream(Material.values()).map(Material::name).toList();
                default -> List.of();
            };
        };
    }

    private static Map<String, ArgumentDefinition> arguments(Object value) {
        Map<String, ArgumentDefinition> result = new LinkedHashMap<>();
        List<Object> values = PaperDataDescriptor.array(value, "command route.arguments");
        for (int index = 0; index < values.size(); index++) {
            String path = "command route.arguments[" + index + ']';
            Map<String, Object> item = PaperDataDescriptor.object(
                    values.get(index), path, ARGUMENT_KEYS, ARGUMENT_KEYS);
            String name = name(item.get("name"), path + ".name");
            ArgumentDefinition definition = new ArgumentDefinition(name,
                    Parser.parse(item.get("parser"), path + ".parser"),
                    SuggestionSource.parse(item.get("suggestions"), path + ".suggestions"));
            if (result.putIfAbsent(name, definition) != null) {
                throw PaperDataDescriptor.invalid(path + ".name", "is duplicated");
            }
        }
        return result;
    }

    private static List<OptionDefinition> options(Object value) {
        List<OptionDefinition> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        Set<String> aliases = new LinkedHashSet<>();
        List<Object> values = PaperDataDescriptor.array(value, "command route.options");
        for (int index = 0; index < values.size(); index++) {
            String path = "command route.options[" + index + ']';
            Map<String, Object> item = PaperDataDescriptor.object(values.get(index), path, OPTION_KEYS, OPTION_KEYS);
            String name = name(item.get("name"), path + ".name");
            if (!names.add(name)) {
                throw PaperDataDescriptor.invalid(path + ".name", "is duplicated");
            }
            List<String> shortAliases = PaperDataDescriptor.strings(item.get("aliases"), path + ".aliases")
                    .stream().map(alias -> shortAlias(alias, path + ".aliases")).toList();
            for (String alias : shortAliases) {
                if (!aliases.add(alias)) {
                    throw PaperDataDescriptor.invalid(path + ".aliases", "contains a duplicate alias: " + alias);
                }
            }
            result.add(new OptionDefinition(name,
                    Parser.parse(item.get("parser"), path + ".parser"), shortAliases,
                    SuggestionSource.parse(item.get("suggestions"), path + ".suggestions"),
                    PaperDataDescriptor.bool(item.get("required"), path + ".required")));
        }
        return List.copyOf(result);
    }

    private static List<SyntaxNode> syntax(String syntax, Map<String, ArgumentDefinition> arguments) {
        if (syntax.isEmpty()) {
            if (!arguments.isEmpty()) {
                throw PaperDataDescriptor.invalid("command route.arguments", "contains names absent from syntax");
            }
            return List.of();
        }
        List<SyntaxNode> result = new ArrayList<>();
        Set<String> used = new LinkedHashSet<>();
        boolean optional = false;
        String[] tokens = syntax.split("\\s+");
        for (int index = 0; index < tokens.length; index++) {
            String token = tokens[index];
            boolean required = token.startsWith("<") && token.endsWith(">");
            boolean optionalToken = token.startsWith("[") && token.endsWith("]");
            if (!required && !optionalToken) {
                if (token.contains("<") || token.contains(">") || token.contains("[") || token.contains("]")) {
                    throw PaperDataDescriptor.invalid("command route.syntax", "has malformed token: " + token);
                }
                if (optional) {
                    throw PaperDataDescriptor.invalid("command route.syntax", "literal follows an optional argument");
                }
                result.add(new LiteralNode(token));
                continue;
            }
            String argumentName = token.substring(1, token.length() - 1);
            boolean greedy = argumentName.endsWith("...");
            if (greedy) {
                argumentName = argumentName.substring(0, argumentName.length() - 3);
            }
            ArgumentDefinition definition = arguments.get(argumentName);
            if (definition == null || !used.add(argumentName)) {
                throw PaperDataDescriptor.invalid("command route.syntax",
                        "references an unknown or duplicate argument: " + argumentName);
            }
            if (optional && required) {
                throw PaperDataDescriptor.invalid("command route.syntax", "required argument follows an optional one");
            }
            if (greedy && index != tokens.length - 1) {
                throw PaperDataDescriptor.invalid("command route.syntax", "greedy argument must be last");
            }
            if (greedy && definition.parser() != Parser.STRING) {
                throw PaperDataDescriptor.invalid("command route.syntax", "greedy argument must use string parser");
            }
            optional |= optionalToken;
            result.add(new ArgumentNode(definition, required, greedy));
        }
        if (!used.equals(arguments.keySet())) {
            throw PaperDataDescriptor.invalid("command route.arguments", "contains names absent from syntax");
        }
        return List.copyOf(result);
    }

    private static Specificity calculateSpecificity(
            List<SyntaxNode> nodes, List<OptionDefinition> options, SenderKind sender) {
        int literals = 0;
        int required = 0;
        int typed = 0;
        int fixed = 0;
        for (SyntaxNode node : nodes) {
            if (node instanceof LiteralNode) {
                literals++;
                fixed++;
            } else if (node instanceof ArgumentNode argument) {
                required += argument.required() ? 1 : 0;
                typed += argument.definition().parser().specificity();
                fixed += argument.greedy() ? 0 : 1;
            }
        }
        required += (int) options.stream().filter(OptionDefinition::required).count();
        return new Specificity(literals, sender == SenderKind.ANY ? 0 : 1, required, typed, fixed, nodes.size());
    }

    private static String name(Object value, String path) {
        String result = PaperDataDescriptor.text(value, path, false);
        if (!result.matches("[A-Za-z][A-Za-z0-9_-]{0,63}")) {
            throw PaperDataDescriptor.invalid(path, "is not a valid name");
        }
        return result;
    }

    private static String shortAlias(String value, String path) {
        String alias = value.startsWith("-") ? value.substring(1) : value;
        if (!alias.matches("[A-Za-z0-9]")) {
            throw PaperDataDescriptor.invalid(path, "must contain one-character aliases");
        }
        return alias;
    }

    private static void duplicate(Object previous, String field) {
        if (previous != null) {
            throw PaperDataDescriptor.invalid("command route", "duplicates " + field);
        }
    }

    private static boolean startsWith(String value, String prefix) {
        return value.regionMatches(true, 0, prefix, 0, prefix.length());
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted(SUGGESTION_ORDER).toList();
    }

    record Match(Map<String, Object> arguments, Map<String, Object> options) {
    }

    record Specificity(int literals, int sender, int required, int typed, int fixed, int nodes)
            implements Comparable<Specificity> {
        @Override
        public int compareTo(Specificity other) {
            int result = Integer.compare(literals, other.literals);
            result = result != 0 ? result : Integer.compare(sender, other.sender);
            result = result != 0 ? result : Integer.compare(required, other.required);
            result = result != 0 ? result : Integer.compare(typed, other.typed);
            result = result != 0 ? result : Integer.compare(fixed, other.fixed);
            return result != 0 ? result : Integer.compare(nodes, other.nodes);
        }
    }

    private sealed interface SyntaxNode permits LiteralNode, ArgumentNode {
    }

    private record LiteralNode(String value) implements SyntaxNode {
    }

    private record ArgumentNode(ArgumentDefinition definition, boolean required, boolean greedy)
            implements SyntaxNode {
    }

    private record ArgumentDefinition(String name, Parser parser, SuggestionSource suggestions) {
    }

    private record OptionDefinition(
            String name, Parser parser, List<String> aliases, SuggestionSource suggestions, boolean required) {
    }

    private record OptionScan(List<String> positionals, Map<String, Object> values) {
    }

    private record CompletionScan(List<String> positionals, Set<String> used, OptionDefinition awaiting,
            boolean optionsEnded) {
    }

    private record OptionToken(OptionDefinition option, String value) {
    }

    private record OptionValue(OptionDefinition option, String prefix, String filter) {
    }

    private record ParseValue(boolean matched, Object value) {
        private static ParseValue noMatch() {
            return new ParseValue(false, null);
        }

        private static ParseValue match(Object value) {
            return new ParseValue(true, value);
        }
    }

    private record SuggestionSource(SuggestionKind kind, List<String> values) {
        private static SuggestionSource parse(Object value, String path) {
            if (value instanceof String magic) {
                return switch (magic) {
                    case "players" -> new SuggestionSource(SuggestionKind.PLAYERS, List.of());
                    case "materials" -> new SuggestionSource(SuggestionKind.MATERIALS, List.of());
                    default -> throw PaperDataDescriptor.invalid(path, "unknown magic suggestion: " + magic);
                };
            }
            List<String> values = PaperDataDescriptor.strings(value, path);
            if (values.stream().anyMatch(String::isBlank) || new LinkedHashSet<>(values).size() != values.size()) {
                throw PaperDataDescriptor.invalid(path, "must contain unique non-blank strings");
            }
            if (values.equals(List.of("players"))) {
                return new SuggestionSource(SuggestionKind.PLAYERS, List.of());
            }
            if (values.equals(List.of("materials"))) {
                return new SuggestionSource(SuggestionKind.MATERIALS, List.of());
            }
            return new SuggestionSource(values.isEmpty() ? SuggestionKind.NONE : SuggestionKind.STATIC, values);
        }
    }

    private enum SuggestionKind { NONE, STATIC, PLAYERS, MATERIALS }

    private enum SenderKind {
        ANY,
        PLAYER,
        CONSOLE;

        private static SenderKind parse(String value) {
            return switch (value) {
                case "any" -> ANY;
                case "player" -> PLAYER;
                case "console" -> CONSOLE;
                default -> throw PaperDataDescriptor.invalid("command route.sender", "is not supported: " + value);
            };
        }

        private boolean allows(CommandSender sender) {
            return switch (this) {
                case ANY -> true;
                case PLAYER -> sender instanceof Player;
                case CONSOLE -> sender instanceof ConsoleCommandSender;
            };
        }
    }

    private enum Parser {
        STRING(0),
        INTEGER(3),
        NUMBER(2),
        BOOLEAN(3),
        PLAYER(4),
        MATERIAL(4);

        private final int specificity;

        Parser(int specificity) {
            this.specificity = specificity;
        }

        private static Parser parse(Object value, String path) {
            String parser = PaperDataDescriptor.text(value, path, false);
            return switch (parser) {
                case "string" -> STRING;
                case "integer" -> INTEGER;
                case "number" -> NUMBER;
                case "boolean" -> BOOLEAN;
                case "player" -> PLAYER;
                case "material" -> MATERIAL;
                default -> throw PaperDataDescriptor.invalid(path, "is not supported: " + parser);
            };
        }

        private ParseValue parse(Server server, String value) {
            try {
                return switch (this) {
                    case STRING -> value.isEmpty() ? ParseValue.noMatch() : ParseValue.match(value);
                    case INTEGER -> ParseValue.match(Integer.valueOf(value));
                    case NUMBER -> {
                        double number = Double.parseDouble(value);
                        yield Double.isFinite(number) ? ParseValue.match(number) : ParseValue.noMatch();
                    }
                    case BOOLEAN -> "true".equalsIgnoreCase(value) ? ParseValue.match(true)
                            : "false".equalsIgnoreCase(value) ? ParseValue.match(false) : ParseValue.noMatch();
                    case PLAYER -> {
                        Player player = server.getPlayerExact(value);
                        yield player == null ? ParseValue.noMatch()
                                : ParseValue.match(PaperCommandContextBridge.playerData(player));
                    }
                    case MATERIAL -> {
                        Material material = Material.getMaterial(value.toUpperCase(Locale.ROOT));
                        yield material == null ? ParseValue.noMatch() : ParseValue.match(material.name());
                    }
                };
            } catch (NumberFormatException ignored) {
                return ParseValue.noMatch();
            }
        }

        private int specificity() {
            return specificity;
        }
    }
}
