package dev.shamoo.runtime.platform.paper;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Strict, bounded, data-only model assembled from the managed lobby YAML files. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.PreserveStackTrace"})
public record ManagedLobbyConfig(
        Join join,
        double voidRescueY,
        Protection protection,
        long defaultPortalCooldownMillis,
        int enforcementTicks,
        List<ManagedWorld> worlds,
        Visibility visibility,
        Transfers transfers,
        Spawn spawn,
        Map<String, LobbyItem> items,
        Map<String, Menu> menus,
        Sidebar sidebar,
        List<ManagedLobbyPortalIndex.Portal> portals,
        Map<String, LobbyTitle> titles,
        Map<String, LobbySound> sounds,
        Map<String, LobbyParticle> particles,
        Map<String, String> messages) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");
    private static final Pattern TARGET = Pattern.compile("[A-Za-z0-9._-]{1,64}");
    private static final Pattern PERMISSION = Pattern.compile("[A-Za-z0-9._-]{1,128}");
    private static final int MAX_TEXT = 4_096;
    private static final int MAX_COLLECTION = 256;
    private static final int MAX_DEPTH = 32;

    public ManagedLobbyConfig {
        Objects.requireNonNull(join, "join");
        Objects.requireNonNull(protection, "protection");
        worlds = List.copyOf(worlds);
        Objects.requireNonNull(visibility, "visibility");
        Objects.requireNonNull(transfers, "transfers");
        items = Map.copyOf(items);
        menus = Map.copyOf(menus);
        Objects.requireNonNull(sidebar, "sidebar");
        portals = List.copyOf(portals);
        titles = Map.copyOf(titles);
        sounds = Map.copyOf(sounds);
        particles = Map.copyOf(particles);
        messages = Map.copyOf(messages);
    }

    public static ManagedLobbyConfig parse(Map<String, String> files) {
        Objects.requireNonNull(files, "files");
        if (!files.keySet().equals(Set.copyOf(ManagedLobbyStore.FILES))) {
            throw invalid("files", "must contain exactly " + ManagedLobbyStore.FILES);
        }
        Map<String, Object> config = yaml(files.get("config.yml"), "config.yml");
        exact(config, "config.yml", Set.of("join", "void-rescue-y", "protection", "portal-cooldown-ms",
                "enforcement-ticks", "worlds", "visibility", "transfers"));
        Join join = parseJoin(optionalObject(config, "join", "config.yml.join"));
        double rescue = optionalDouble(config, "void-rescue-y", -80, -2_048, 2_048,
                "config.yml.void-rescue-y");
        Protection protection = parseProtection(optionalObject(config, "protection", "config.yml.protection"));
        long portalCooldown = optionalInteger(config, "portal-cooldown-ms", 2_500, 0, 600_000,
                "config.yml.portal-cooldown-ms");
        int enforcement = optionalInteger(config, "enforcement-ticks", 200, 20, 12_000,
                "config.yml.enforcement-ticks");
        List<ManagedWorld> worlds = parseWorlds(optionalList(config, "worlds", "config.yml.worlds"));
        Visibility visibility = parseVisibility(optionalObject(config, "visibility", "config.yml.visibility"));
        long transferCooldown = parseTransferCooldown(optionalObject(config, "transfers", "config.yml.transfers"));
        Map<String, Server> servers = parseServers(sectionList(files, "servers.yml", "servers"));
        Transfers transfers = new Transfers(servers, transferCooldown);
        Spawn spawn = parseSpawnFile(files.get("spawn.yml"));
        Map<String, LobbyItem> items = parseItems(sectionList(files, "items.yml", "items"));
        Map<String, Menu> menus = parseMenus(sectionList(files, "menus.yml", "menus"));
        Sidebar sidebar = parseSidebar(files.get("scoreboard.yml"));
        List<ManagedLobbyPortalIndex.Portal> portals = parsePortals(
                sectionList(files, "portals.yml", "portals"), portalCooldown);
        Messages messages = parseMessages(files.get("messages.yml"));
        validateReferences(join, items, menus, portals, messages, transfers, worlds);
        return new ManagedLobbyConfig(join, rescue, protection, portalCooldown, enforcement, worlds, visibility,
                transfers, spawn, items, menus, sidebar, portals, messages.titles(), messages.sounds(),
                messages.particles(), messages.messages());
    }

    public boolean manages(String world) {
        return worlds.stream().anyMatch(configured -> configured.name().equals(world));
    }

    public static String encodeSpawn(Spawn spawn) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("configured", spawn != null);
        if (spawn != null) {
            value.putAll(spawn.data());
        }
        return dump(Map.of("spawn", value));
    }

    public static String encodePortals(List<ManagedLobbyPortalIndex.Portal> portals) {
        return dump(Map.of("portals", portals.stream().map(ManagedLobbyConfig::portalData).toList()));
    }

    public static Map<String, Object> portalData(ManagedLobbyPortalIndex.Portal portal) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("id", portal.id());
        value.put("enabled", portal.enabled());
        value.put("world", portal.world());
        value.put("min", coordinate(portal.minimumX(), portal.minimumY(), portal.minimumZ()));
        value.put("max", coordinate(portal.maximumX(), portal.maximumY(), portal.maximumZ()));
        if (portal.permission() != null) {
            value.put("permission", portal.permission());
        }
        value.put("priority", portal.priority());
        value.put("cooldown-ms", portal.cooldownMillis());
        if (portal.destination() != null) {
            value.put("destination", portal.destination());
        }
        value.put("action", portal.action().data());
        value.put("visualize", portal.visualize());
        return java.util.Collections.unmodifiableMap(value);
    }

    private static Join parseJoin(Map<String, Object> value) {
        String path = "config.yml.join";
        exact(value, path, Set.of("suppress-message", "teleport", "reset", "welcome-title", "welcome-sound",
                "welcome-particle", "welcome-message"));
        return new Join(optionalBoolean(value, "suppress-message", true, path + ".suppress-message"),
                optionalBoolean(value, "teleport", true, path + ".teleport"),
                optionalBoolean(value, "reset", true, path + ".reset"),
                optionalId(value, "welcome-title", path), optionalId(value, "welcome-sound", path),
                optionalId(value, "welcome-particle", path), optionalId(value, "welcome-message", path));
    }

    private static Protection parseProtection(Map<String, Object> value) {
        String path = "config.yml.protection";
        exact(value, path, Set.of("enabled", "bypass-permission"));
        String bypass = optionalText(value, "bypass-permission", "lobby.protection.bypass",
                path + ".bypass-permission");
        if (!PERMISSION.matcher(bypass).matches()) {
            throw invalid(path + ".bypass-permission", "is not a permission node");
        }
        return new Protection(optionalBoolean(value, "enabled", true, path + ".enabled"), bypass);
    }

    private static List<ManagedWorld> parseWorlds(List<Object> values) {
        bounded(values, 32, "config.yml.worlds");
        List<ManagedWorld> result = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "config.yml.worlds[" + index + ']';
            Map<String, Object> value = object(values.get(index), path);
            exact(value, path, Set.of("name", "time", "storm", "thundering", "game-rules"));
            String name = text(required(value, "name", path), path + ".name", 64, false);
            if (!names.add(name)) {
                throw invalid(path + ".name", "is duplicated");
            }
            Long time = value.get("time") == null ? null
                    : (long) integer(value.get("time"), path + ".time", 0, 24_000);
            result.add(new ManagedWorld(name, time, nullableBoolean(value.get("storm"), path + ".storm"),
                    nullableBoolean(value.get("thundering"), path + ".thundering"),
                    stringScalarMap(value.get("game-rules"), path + ".game-rules", 64)));
        }
        return List.copyOf(result);
    }

    private static Visibility parseVisibility(Map<String, Object> value) {
        String path = "config.yml.visibility";
        exact(value, path, Set.of("default", "staff-permission"));
        VisibilityMode mode = visibilityMode(optionalText(value, "default", "all", path + ".default"),
                path + ".default", false);
        String permission = optionalText(value, "staff-permission", "lobby.visibility.staff",
                path + ".staff-permission");
        if (!PERMISSION.matcher(permission).matches()) {
            throw invalid(path + ".staff-permission", "is not a permission node");
        }
        return new Visibility(mode, permission);
    }

    private static long parseTransferCooldown(Map<String, Object> value) {
        exact(value, "config.yml.transfers", Set.of("cooldown-ms"));
        return optionalInteger(value, "cooldown-ms", 3_000, 0, 600_000,
                "config.yml.transfers.cooldown-ms");
    }

    private static Map<String, Server> parseServers(List<Object> values) {
        bounded(values, 64, "servers.yml.servers");
        Map<String, Server> result = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "servers.yml.servers[" + index + ']';
            Map<String, Object> value = object(values.get(index), path);
            exact(value, path, Set.of("id", "enabled", "target", "display-name"));
            String serverId = id(required(value, "id", path), path + ".id");
            String target = text(required(value, "target", path), path + ".target", 64, false);
            if (!TARGET.matcher(target).matches()) {
                throw invalid(path + ".target", "is not a valid BungeeCord server target");
            }
            Server server = new Server(serverId, optionalBoolean(value, "enabled", true, path + ".enabled"), target,
                    optionalText(value, "display-name", serverId, path + ".display-name"));
            duplicate(result.putIfAbsent(serverId, server), path + ".id");
        }
        return result;
    }

    private static Spawn parseSpawnFile(String source) {
        Map<String, Object> root = yaml(source, "spawn.yml");
        exact(root, "spawn.yml", Set.of("spawn"));
        Map<String, Object> value = object(required(root, "spawn", "spawn.yml"), "spawn.yml.spawn");
        boolean configured = bool(required(value, "configured", "spawn.yml.spawn"),
                "spawn.yml.spawn.configured");
        if (!configured) {
            exact(value, "spawn.yml.spawn", Set.of("configured"));
            return null;
        }
        String path = "spawn.yml.spawn";
        exact(value, path, Set.of("configured", "world", "x", "y", "z", "yaw", "pitch"));
        return new Spawn(text(required(value, "world", path), path + ".world", 64, false),
                number(required(value, "x", path), path + ".x", -30_000_000, 30_000_000),
                number(required(value, "y", path), path + ".y", -2_048, 2_048),
                number(required(value, "z", path), path + ".z", -30_000_000, 30_000_000),
                (float) number(required(value, "yaw", path), path + ".yaw", -360, 360),
                (float) number(required(value, "pitch", path), path + ".pitch", -90, 90));
    }

    private static Map<String, LobbyItem> parseItems(List<Object> values) {
        bounded(values, 36, "items.yml.items");
        Map<String, LobbyItem> result = new LinkedHashMap<>();
        Set<Integer> slots = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "items.yml.items[" + index + ']';
            Map<String, Object> value = object(values.get(index), path);
            exact(value, path, Set.of("id", "slot", "material", "amount", "name", "lore", "cooldown-ms",
                    "action"));
            LobbyItem item = new LobbyItem(id(required(value, "id", path), path + ".id"),
                    integer(required(value, "slot", path), path + ".slot", 0, 35),
                    enumText(required(value, "material", path), path + ".material"),
                    optionalInteger(value, "amount", 1, 1, 99, path + ".amount"),
                    optionalText(value, "name", "", path + ".name"),
                    strings(value.get("lore"), path + ".lore", 32, MAX_TEXT),
                    optionalInteger(value, "cooldown-ms", 0, 0, 600_000, path + ".cooldown-ms"),
                    value.get("action") == null ? Action.none() : parseAction(value.get("action"), path + ".action"));
            if (!slots.add(item.slot())) {
                throw invalid(path + ".slot", "is duplicated");
            }
            duplicate(result.putIfAbsent(item.id(), item), path + ".id");
        }
        return result;
    }

    private static Map<String, Menu> parseMenus(List<Object> values) {
        bounded(values, 64, "menus.yml.menus");
        Map<String, Menu> result = new LinkedHashMap<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "menus.yml.menus[" + index + ']';
            Map<String, Object> value = object(values.get(index), path);
            exact(value, path, Set.of("id", "rows", "title", "slots"));
            String menuId = id(required(value, "id", path), path + ".id");
            int rows = integer(required(value, "rows", path), path + ".rows", 1, 6);
            List<Object> slots = list(value.get("slots"), path + ".slots");
            bounded(slots, rows * 9, path + ".slots");
            Map<Integer, MenuSlot> parsed = new LinkedHashMap<>();
            for (int slotIndex = 0; slotIndex < slots.size(); slotIndex++) {
                String slotPath = path + ".slots[" + slotIndex + ']';
                Map<String, Object> slot = object(slots.get(slotIndex), slotPath);
                exact(slot, slotPath, Set.of("slot", "material", "amount", "name", "lore", "action"));
                int position = integer(required(slot, "slot", slotPath), slotPath + ".slot", 0, rows * 9 - 1);
                MenuSlot menuSlot = new MenuSlot(position,
                        enumText(required(slot, "material", slotPath), slotPath + ".material"),
                        optionalInteger(slot, "amount", 1, 1, 99, slotPath + ".amount"),
                        optionalText(slot, "name", "", slotPath + ".name"),
                        strings(slot.get("lore"), slotPath + ".lore", 32, MAX_TEXT),
                        slot.get("action") == null ? Action.none() : parseAction(slot.get("action"),
                                slotPath + ".action"));
                duplicate(parsed.putIfAbsent(position, menuSlot), slotPath + ".slot");
            }
            duplicate(result.putIfAbsent(menuId, new Menu(menuId, rows,
                    text(required(value, "title", path), path + ".title", MAX_TEXT, true), parsed)), path + ".id");
        }
        return result;
    }

    private static Sidebar parseSidebar(String source) {
        Map<String, Object> root = yaml(source, "scoreboard.yml");
        exact(root, "scoreboard.yml", Set.of("sidebar"));
        String path = "scoreboard.yml.sidebar";
        Map<String, Object> value = optionalObject(root, "sidebar", path);
        exact(value, path, Set.of("enabled", "title", "title-frames", "lines", "interval-ticks"));
        List<String> frames = strings(value.get("title-frames"), path + ".title-frames", 32, MAX_TEXT);
        if (frames.isEmpty()) {
            frames = List.of(optionalText(value, "title", "<gold>Lobby</gold>", path + ".title"));
        }
        return new Sidebar(optionalBoolean(value, "enabled", false, path + ".enabled"), frames,
                strings(value.get("lines"), path + ".lines", 15, MAX_TEXT),
                optionalInteger(value, "interval-ticks", 20, 5, 1_200, path + ".interval-ticks"));
    }

    private static List<ManagedLobbyPortalIndex.Portal> parsePortals(List<Object> values, long defaultCooldown) {
        bounded(values, MAX_COLLECTION, "portals.yml.portals");
        List<ManagedLobbyPortalIndex.Portal> result = new ArrayList<>();
        Set<String> ids = new LinkedHashSet<>();
        for (int index = 0; index < values.size(); index++) {
            String path = "portals.yml.portals[" + index + ']';
            Map<String, Object> value = object(values.get(index), path);
            exact(value, path, Set.of("id", "enabled", "world", "min", "max", "permission", "priority",
                    "cooldown-ms", "destination", "action", "visualize"));
            String portalId = id(required(value, "id", path), path + ".id");
            if (!ids.add(portalId)) {
                throw invalid(path + ".id", "is duplicated");
            }
            Coordinate minimum = coordinate(required(value, "min", path), path + ".min");
            Coordinate maximum = coordinate(required(value, "max", path), path + ".max");
            String permission = nullablePermission(value.get("permission"), path + ".permission");
            String destination = value.get("destination") == null ? null
                    : id(value.get("destination"), path + ".destination");
            Action action = value.get("action") == null
                    ? destination == null ? Action.none() : new Action(ActionType.CONNECT, destination)
                    : parseAction(value.get("action"), path + ".action");
            if (destination != null && (action.type() != ActionType.CONNECT || !destination.equals(action.target()))) {
                throw invalid(path, "destination must match the connect action target");
            }
            result.add(new ManagedLobbyPortalIndex.Portal(portalId,
                    text(required(value, "world", path), path + ".world", 64, false),
                    minimum.x(), minimum.y(), minimum.z(), maximum.x(), maximum.y(), maximum.z(),
                    optionalBoolean(value, "enabled", true, path + ".enabled"), permission,
                    optionalInteger(value, "priority", 0, -10_000, 10_000, path + ".priority"),
                    optionalInteger(value, "cooldown-ms", (int) defaultCooldown, 0, 600_000,
                            path + ".cooldown-ms"), destination, action,
                    optionalBoolean(value, "visualize", false, path + ".visualize")));
        }
        return List.copyOf(result);
    }

    private static Messages parseMessages(String source) {
        Map<String, Object> root = yaml(source, "messages.yml");
        exact(root, "messages.yml", Set.of("messages", "titles", "sounds", "particles"));
        Map<String, String> messages = stringMap(root.get("messages"), "messages.yml.messages");
        Map<String, LobbyTitle> titles = new LinkedHashMap<>();
        List<Object> titleValues = list(root.get("titles"), "messages.yml.titles");
        bounded(titleValues, 64, "messages.yml.titles");
        for (int index = 0; index < titleValues.size(); index++) {
            String path = "messages.yml.titles[" + index + ']';
            Map<String, Object> value = object(titleValues.get(index), path);
            exact(value, path, Set.of("id", "title", "subtitle", "fade-in-ticks", "stay-ticks",
                    "fade-out-ticks"));
            String effectId = id(required(value, "id", path), path + ".id");
            LobbyTitle title = new LobbyTitle(effectId, optionalText(value, "title", "", path + ".title"),
                    optionalText(value, "subtitle", "", path + ".subtitle"),
                    optionalInteger(value, "fade-in-ticks", 10, 0, 1_200, path + ".fade-in-ticks"),
                    optionalInteger(value, "stay-ticks", 70, 0, 12_000, path + ".stay-ticks"),
                    optionalInteger(value, "fade-out-ticks", 20, 0, 1_200, path + ".fade-out-ticks"));
            duplicate(titles.putIfAbsent(effectId, title), path + ".id");
        }
        Map<String, LobbySound> sounds = new LinkedHashMap<>();
        List<Object> soundValues = list(root.get("sounds"), "messages.yml.sounds");
        bounded(soundValues, 64, "messages.yml.sounds");
        for (int index = 0; index < soundValues.size(); index++) {
            String path = "messages.yml.sounds[" + index + ']';
            Map<String, Object> value = object(soundValues.get(index), path);
            exact(value, path, Set.of("id", "sound", "volume", "pitch"));
            String effectId = id(required(value, "id", path), path + ".id");
            LobbySound sound = new LobbySound(effectId,
                    enumText(required(value, "sound", path), path + ".sound"),
                    (float) optionalDouble(value, "volume", 1, 0, 16, path + ".volume"),
                    (float) optionalDouble(value, "pitch", 1, 0, 2, path + ".pitch"));
            duplicate(sounds.putIfAbsent(effectId, sound), path + ".id");
        }
        Map<String, LobbyParticle> particles = new LinkedHashMap<>();
        List<Object> particleValues = list(root.get("particles"), "messages.yml.particles");
        bounded(particleValues, 64, "messages.yml.particles");
        for (int index = 0; index < particleValues.size(); index++) {
            String path = "messages.yml.particles[" + index + ']';
            Map<String, Object> value = object(particleValues.get(index), path);
            exact(value, path, Set.of("id", "particle", "count", "offset-x", "offset-y", "offset-z", "speed"));
            String effectId = id(required(value, "id", path), path + ".id");
            LobbyParticle particle = new LobbyParticle(effectId,
                    enumText(required(value, "particle", path), path + ".particle"),
                    optionalInteger(value, "count", 1, 0, 1_000, path + ".count"),
                    optionalDouble(value, "offset-x", 0, 0, 128, path + ".offset-x"),
                    optionalDouble(value, "offset-y", 0, 0, 128, path + ".offset-y"),
                    optionalDouble(value, "offset-z", 0, 0, 128, path + ".offset-z"),
                    optionalDouble(value, "speed", 0, 0, 16, path + ".speed"));
            duplicate(particles.putIfAbsent(effectId, particle), path + ".id");
        }
        return new Messages(messages, titles, sounds, particles);
    }

    static Action parseAction(Object raw, String path) {
        Map<String, Object> value = object(raw, path);
        exact(value, path, Set.of("type", "target"));
        ActionType type;
        try {
            type = ActionType.valueOf(text(required(value, "type", path), path + ".type", 32, false)
                    .toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException exception) {
            throw invalid(path + ".type", "is not a supported native action");
        }
        String target = value.get("target") == null ? null : text(value.get("target"), path + ".target", 64, false);
        if (type.requiresTarget() != (target != null)) {
            throw invalid(path + ".target", type.requiresTarget() ? "is required" : "is not accepted");
        }
        if (type == ActionType.VISIBILITY) {
            visibilityMode(target, path + ".target", true);
        } else if (target != null && !ID.matcher(target).matches()) {
            throw invalid(path + ".target", "must be a bounded identifier");
        }
        return new Action(type, target);
    }

    private static void validateReferences(Join join, Map<String, LobbyItem> items, Map<String, Menu> menus,
            List<ManagedLobbyPortalIndex.Portal> portals, Messages effects, Transfers transfers,
            List<ManagedWorld> worlds) {
        reference(join.welcomeTitle(), effects.titles(), "join.welcome-title");
        reference(join.welcomeSound(), effects.sounds(), "join.welcome-sound");
        reference(join.welcomeParticle(), effects.particles(), "join.welcome-particle");
        reference(join.welcomeMessage(), effects.messages(), "join.welcome-message");
        Set<String> managedWorlds = worlds.stream().map(ManagedWorld::name)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        List<Action> actions = new ArrayList<>();
        items.values().forEach(item -> actions.add(item.action()));
        menus.values().forEach(menu -> menu.slots().values().forEach(slot -> actions.add(slot.action())));
        portals.forEach(portal -> {
            if (!managedWorlds.contains(portal.world())) {
                throw invalid("portal.world", "references an unmanaged world: " + portal.world());
            }
            actions.add(portal.action());
        });
        for (Action action : actions) {
            boolean valid = switch (action.type()) {
                case MENU -> menus.containsKey(action.target());
                case TITLE -> effects.titles().containsKey(action.target());
                case SOUND -> effects.sounds().containsKey(action.target());
                case PARTICLE -> effects.particles().containsKey(action.target());
                case CONNECT -> transfers.enabled(action.target()) != null;
                default -> true;
            };
            if (!valid) {
                throw invalid("action.target", "references an unavailable "
                        + action.type().name().toLowerCase(Locale.ROOT) + ": " + action.target());
            }
        }
    }

    private static void reference(String id, Map<String, ?> values, String path) {
        if (id != null && !values.containsKey(id)) {
            throw invalid(path, "references an undefined id: " + id);
        }
    }

    private static Map<String, Object> yaml(String source, String path) {
        if (source == null || source.getBytes(StandardCharsets.UTF_8).length > ManagedLobbyStore.MAX_FILE_BYTES) {
            throw invalid(path, "is missing or exceeds 1 MiB");
        }
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        options.setMaxAliasesForCollections(16);
        options.setNestingDepthLimit(MAX_DEPTH);
        options.setCodePointLimit(ManagedLobbyStore.MAX_FILE_BYTES);
        Object loaded;
        try {
            loaded = new Yaml(new SafeConstructor(options)).load(source);
        } catch (RuntimeException exception) {
            throw invalid(path, "is not safe valid YAML: " + exception.getMessage());
        }
        return loaded == null ? new LinkedHashMap<>() : dataObject(loaded, path, 0);
    }

    private static Map<String, Object> dataObject(Object raw, String path, int depth) {
        if (!(raw instanceof Map<?, ?> map)) {
            throw invalid(path, "must have an object root");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String text) || text.length() > 128) {
                throw invalid(path, "contains a non-string or oversized key");
            }
            result.put(text, data(value, path + '.' + text, depth + 1));
        });
        return result;
    }

    private static Object data(Object value, String path, int depth) {
        if (depth > MAX_DEPTH) {
            throw invalid(path, "exceeds the maximum nesting depth");
        }
        if (value == null || value instanceof String || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Number number) {
            if (!Double.isFinite(number.doubleValue())) {
                throw invalid(path, "must be finite");
            }
            return value;
        }
        if (value instanceof List<?> list) {
            bounded(list, MAX_COLLECTION, path);
            return list.stream().map(item -> data(item, path, depth + 1)).toList();
        }
        if (value instanceof Map<?, ?>) {
            return dataObject(value, path, depth);
        }
        throw invalid(path, "contains a non-data YAML value");
    }

    private static List<Object> sectionList(Map<String, String> files, String file, String section) {
        Map<String, Object> root = yaml(files.get(file), file);
        exact(root, file, Set.of(section));
        return list(root.get(section), file + '.' + section);
    }

    private static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> source)) {
            throw invalid(path, "must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String text)) {
                throw invalid(path, "contains a non-string key");
            }
            result.put(text, item);
        });
        return result;
    }

    private static Map<String, Object> optionalObject(Map<String, Object> value, String key, String path) {
        return value.get(key) == null ? new LinkedHashMap<>() : object(value.get(key), path);
    }

    private static List<Object> list(Object value, String path) {
        if (value == null) {
            return List.of();
        }
        if (!(value instanceof List<?> source)) {
            throw invalid(path, "must be an array");
        }
        return new ArrayList<>(source);
    }

    private static List<Object> optionalList(Map<String, Object> value, String key, String path) {
        return list(value.get(key), path);
    }

    private static List<String> strings(Object value, String path, int maximum, int maximumLength) {
        List<Object> source = list(value, path);
        bounded(source, maximum, path);
        return source.stream().map(item -> text(item, path, maximumLength, true)).toList();
    }

    private static Map<String, String> stringMap(Object raw, String path) {
        if (raw == null) {
            return Map.of();
        }
        Map<String, Object> source = object(raw, path);
        bounded(source.entrySet(), MAX_COLLECTION, path);
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, value) -> result.put(id(key, path + " key"), text(value, path + '.' + key,
                MAX_TEXT, true)));
        return result;
    }

    private static String text(Object value, String path, int maximum, boolean allowEmpty) {
        if (!(value instanceof String text) || text.length() > maximum || (!allowEmpty && text.isBlank())) {
            throw invalid(path, "must be bounded " + (allowEmpty ? "text" : "non-blank text"));
        }
        return text;
    }

    private static String optionalText(Map<String, Object> value, String key, String fallback, String path) {
        return value.get(key) == null ? fallback : text(value.get(key), path, MAX_TEXT, true);
    }

    private static String optionalId(Map<String, Object> value, String key, String path) {
        return value.get(key) == null ? null : id(value.get(key), path + '.' + key);
    }

    private static String enumText(Object value, String path) {
        String result = text(value, path, 128, false).toUpperCase(Locale.ROOT);
        if (!result.matches("[A-Z0-9_.:-]+")) {
            throw invalid(path, "is not a namespaced or enum identifier");
        }
        return result;
    }

    static String id(Object value, String path) {
        String result = text(value, path, 64, false);
        if (!ID.matcher(result).matches()) {
            throw invalid(path, "must match " + ID.pattern());
        }
        return result;
    }

    private static String nullablePermission(Object value, String path) {
        if (value == null) {
            return null;
        }
        String permission = text(value, path, 128, false);
        if (!PERMISSION.matcher(permission).matches()) {
            throw invalid(path, "is not a permission node");
        }
        return permission;
    }

    private static Object required(Map<String, Object> value, String key, String path) {
        Object result = value.get(key);
        if (result == null) {
            throw invalid(path + '.' + key, "is required");
        }
        return result;
    }

    private static void exact(Map<String, Object> value, String path, Set<String> allowed) {
        for (String key : value.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid(path + '.' + key, "is not a supported key");
            }
        }
    }

    private static boolean optionalBoolean(Map<String, Object> value, String key, boolean fallback, String path) {
        return value.get(key) == null ? fallback : bool(value.get(key), path);
    }

    private static Boolean nullableBoolean(Object value, String path) {
        return value == null ? null : bool(value, path);
    }

    private static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean result)) {
            throw invalid(path, "must be a boolean");
        }
        return result;
    }

    private static int optionalInteger(Map<String, Object> value, String key, int fallback,
            int minimum, int maximum, String path) {
        return value.get(key) == null ? fallback : integer(value.get(key), path, minimum, maximum);
    }

    private static int integer(Object value, String path, int minimum, int maximum) {
        if (!(value instanceof Number number) || number.doubleValue() != number.longValue()
                || number.longValue() < minimum || number.longValue() > maximum) {
            throw invalid(path, "must be an integer from " + minimum + " through " + maximum);
        }
        return number.intValue();
    }

    private static double optionalDouble(Map<String, Object> value, String key, double fallback,
            double minimum, double maximum, String path) {
        return value.get(key) == null ? fallback : number(value.get(key), path, minimum, maximum);
    }

    private static double number(Object value, String path, double minimum, double maximum) {
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() < minimum || number.doubleValue() > maximum) {
            throw invalid(path, "must be a finite number from " + minimum + " through " + maximum);
        }
        return number.doubleValue();
    }

    private static Coordinate coordinate(Object value, String path) {
        Map<String, Object> coordinate = object(value, path);
        exact(coordinate, path, Set.of("x", "y", "z"));
        return new Coordinate(number(required(coordinate, "x", path), path + ".x", -30_000_000, 30_000_000),
                number(required(coordinate, "y", path), path + ".y", -2_048, 2_048),
                number(required(coordinate, "z", path), path + ".z", -30_000_000, 30_000_000));
    }

    private static Map<String, Object> coordinate(double x, double y, double z) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("x", (long) x);
        result.put("y", (long) y);
        result.put("z", (long) z);
        return result;
    }

    private static Map<String, String> stringScalarMap(Object value, String path, int maximum) {
        if (value == null) {
            return Map.of();
        }
        Map<String, Object> source = object(value, path);
        bounded(source.entrySet(), maximum, path);
        Map<String, String> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!key.matches("[A-Za-z0-9_]{1,64}") || !(item instanceof Boolean || item instanceof Number)) {
                throw invalid(path + '.' + key, "must be a boolean or integer game-rule value");
            }
            if (item instanceof Number) {
                integer(item, path + '.' + key, Integer.MIN_VALUE, Integer.MAX_VALUE);
            }
            result.put(key, item.toString());
        });
        return result;
    }

    private static VisibilityMode visibilityMode(String value, String path, boolean cycle) {
        try {
            VisibilityMode mode = VisibilityMode.valueOf(value.toUpperCase(Locale.ROOT));
            if (!cycle && mode == VisibilityMode.CYCLE) {
                throw invalid(path, "cycle is an action, not a stored visibility mode");
            }
            return mode;
        } catch (IllegalArgumentException exception) {
            throw invalid(path, "must be all, none, staff" + (cycle ? ", or cycle" : ""));
        }
    }

    private static void bounded(java.util.Collection<?> values, int maximum, String path) {
        if (values.size() > maximum) {
            throw invalid(path, "contains more than " + maximum + " entries");
        }
    }

    private static void duplicate(Object previous, String path) {
        if (previous != null) {
            throw invalid(path, "is duplicated");
        }
    }

    private static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("invalid " + path + ": " + message);
    }

    private static String dump(Map<String, Object> value) {
        DumperOptions options = new DumperOptions();
        options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        options.setPrettyFlow(true);
        options.setIndent(2);
        return new Yaml(options).dump(value);
    }

    public record Join(boolean suppressMessage, boolean teleport, boolean reset, String welcomeTitle,
            String welcomeSound, String welcomeParticle, String welcomeMessage) {
    }

    public record Protection(boolean enabled, String bypassPermission) {
    }

    public record ManagedWorld(String name, Long time, Boolean storm, Boolean thundering,
            Map<String, String> gameRules) {
        public ManagedWorld {
            gameRules = Map.copyOf(gameRules);
        }
    }

    public record Visibility(VisibilityMode defaultMode, String staffPermission) {
    }

    public enum VisibilityMode {
        ALL, NONE, STAFF, CYCLE
    }

    public record Server(String id, boolean enabled, String target, String displayName) {
        public Map<String, Object> data() {
            return Map.of("id", id, "enabled", enabled, "target", target, "displayName", displayName);
        }
    }

    public record Transfers(Map<String, Server> servers, long cooldownMillis) {
        public Transfers {
            servers = Map.copyOf(servers);
        }

        public Server enabled(String id) {
            Server server = servers.get(id);
            return server != null && server.enabled() ? server : null;
        }

        public Set<String> allowed() {
            return servers.values().stream().filter(Server::enabled).map(Server::id)
                    .collect(java.util.stream.Collectors.toUnmodifiableSet());
        }
    }

    public record Spawn(String world, double x, double y, double z, float yaw, float pitch) {
        Map<String, Object> data() {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("world", world);
            value.put("x", x);
            value.put("y", y);
            value.put("z", z);
            value.put("yaw", yaw);
            value.put("pitch", pitch);
            return value;
        }
    }

    public record LobbyItem(String id, int slot, String material, int amount, String name, List<String> lore,
            long cooldownMillis, Action action) {
        public LobbyItem {
            lore = List.copyOf(lore);
        }
    }

    public record Menu(String id, int rows, String title, Map<Integer, MenuSlot> slots) {
        public Menu {
            slots = Map.copyOf(slots);
        }
    }

    public record MenuSlot(int slot, String material, int amount, String name, List<String> lore, Action action) {
        public MenuSlot {
            lore = List.copyOf(lore);
        }
    }

    public record Sidebar(boolean enabled, List<String> titleFrames, List<String> lines, int intervalTicks) {
        public Sidebar {
            titleFrames = List.copyOf(titleFrames);
            lines = List.copyOf(lines);
        }
    }

    public enum ActionType {
        NONE(false), SPAWN(false), MENU(true), VISIBILITY(true), CONNECT(true), TITLE(true), SOUND(true),
        PARTICLE(true);

        private final boolean targetRequired;

        ActionType(boolean targetRequired) {
            this.targetRequired = targetRequired;
        }

        boolean requiresTarget() {
            return targetRequired;
        }
    }

    public record Action(ActionType type, String target) {
        public Action {
            Objects.requireNonNull(type, "type");
        }

        static Action none() {
            return new Action(ActionType.NONE, null);
        }

        Map<String, Object> data() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("type", type.name().toLowerCase(Locale.ROOT));
            if (target != null) {
                result.put("target", target);
            }
            return result;
        }
    }

    public record LobbyTitle(String id, String title, String subtitle,
            int fadeInTicks, int stayTicks, int fadeOutTicks) {
    }

    public record LobbySound(String id, String sound, float volume, float pitch) {
    }

    public record LobbyParticle(String id, String particle, int count,
            double offsetX, double offsetY, double offsetZ, double speed) {
    }

    private record Coordinate(double x, double y, double z) {
    }

    private record Messages(Map<String, String> messages, Map<String, LobbyTitle> titles,
            Map<String, LobbySound> sounds, Map<String, LobbyParticle> particles) {
        private Messages {
            messages = Map.copyOf(messages);
            titles = Map.copyOf(titles);
            sounds = Map.copyOf(sounds);
            particles = Map.copyOf(particles);
        }
    }
}
