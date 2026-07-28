package dev.shamoo.runtime.platform.paper;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/** Bounded request envelope accepted by the direct managed-lobby host function. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.PreserveStackTrace"})
public record ManagedLobbyRequest(String operation, Map<String, Object> values) {
    private static final Pattern ID = Pattern.compile("[a-z][a-z0-9_-]{0,63}");

    public ManagedLobbyRequest {
        Objects.requireNonNull(operation, "operation");
        values = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(values));
    }

    public static ManagedLobbyRequest parse(Object raw) {
        if (!(raw instanceof Map<?, ?> source)) {
            throw invalid("request must be an object");
        }
        Map<String, Object> values = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            if (!(key instanceof String text)) {
                throw invalid("request keys must be strings");
            }
            values.put(text, value);
        });
        String operation = text(values.get("operation"), "operation", 32);
        switch (operation) {
            case "status", "ensure", "reload" -> exact(values, Set.of("operation"));
            case "read" -> {
                exact(values, Set.of("operation", "file"));
                requireAbsentInsteadOfNull(values, Set.of("file"));
                if (values.get("file") != null) {
                    file(values.get("file"));
                }
            }
            case "write" -> {
                exact(values, Set.of("operation", "file", "content", "reload"));
                file(values.get("file"));
                String content = text(values.get("content"), "content", ManagedLobbyStore.MAX_FILE_BYTES);
                if (content.getBytes(StandardCharsets.UTF_8).length > ManagedLobbyStore.MAX_FILE_BYTES) {
                    throw invalid("content exceeds 1 MiB as UTF-8");
                }
                requireAbsentInsteadOfNull(values, Set.of("reload"));
                optionalBoolean(values.get("reload"), "reload");
            }
            case "execute" -> parseExecute(values);
            default -> throw invalid("unknown operation: " + operation);
        }
        return new ManagedLobbyRequest(operation, values);
    }

    public String text(String key) {
        return text(values.get(key), key, ManagedLobbyStore.MAX_FILE_BYTES);
    }

    public String optionalText(String key) {
        return values.get(key) == null ? null : text(key);
    }

    public boolean optionalBoolean(String key, boolean fallback) {
        return values.get(key) == null ? fallback : bool(values.get(key), key);
    }

    public UUID player() {
        return uuid(values.get("player"));
    }

    public int optionalInteger(String key, int fallback) {
        return values.get(key) == null ? fallback : integer(values.get(key), key);
    }

    private static void parseExecute(Map<String, Object> values) {
        String action = text(values.get("action"), "action", 32);
        switch (action) {
            case "setspawn", "spawn", "items" -> {
                exact(values, Set.of("operation", "action", "player"));
                uuid(values.get("player"));
            }
            case "menu" -> {
                exact(values, Set.of("operation", "action", "player", "id"));
                uuid(values.get("player"));
                id(values.get("id"), "id");
            }
            case "visibility" -> {
                exact(values, Set.of("operation", "action", "player", "mode"));
                uuid(values.get("player"));
                String mode = text(values.get("mode"), "mode", 8);
                if (!Set.of("all", "none", "staff", "cycle").contains(mode)) {
                    throw invalid("mode must be all, none, staff, or cycle");
                }
            }
            case "portal-wand", "portal-pos1", "portal-pos2" -> playerAction(values);
            case "portal-create" -> {
                exact(values, Set.of("operation", "action", "player", "id", "destination", "permission",
                        "priority", "cooldown-ms", "enabled", "visualize"));
                uuid(values.get("player"));
                id(values.get("id"), "id");
                requireAbsentInsteadOfNull(values, Set.of("destination", "permission", "priority", "cooldown-ms",
                        "enabled", "visualize"));
                optionalId(values.get("destination"), "destination");
                optionalPermission(values.get("permission"));
                optionalInteger(values.get("priority"), "priority", -10_000, 10_000);
                optionalInteger(values.get("cooldown-ms"), "cooldown-ms", 0, 600_000);
                optionalBoolean(values.get("enabled"), "enabled");
                optionalBoolean(values.get("visualize"), "visualize");
            }
            case "portal-remove" -> {
                exact(values, Set.of("operation", "action", "player", "id"));
                uuid(values.get("player"));
                id(values.get("id"), "id");
            }
            case "portal-list" -> exact(values, Set.of("operation", "action"));
            case "portal-info" -> {
                exact(values, Set.of("operation", "action", "id"));
                id(values.get("id"), "id");
            }
            case "portal-enable", "portal-disable" -> {
                exact(values, Set.of("operation", "action", "player", "id"));
                uuid(values.get("player"));
                id(values.get("id"), "id");
            }
            case "portal-destination" -> {
                exact(values, Set.of("operation", "action", "player", "id", "type", "target"));
                uuid(values.get("player"));
                id(values.get("id"), "id");
                String type = text(values.get("type"), "type", 8);
                switch (type) {
                    case "server", "menu" -> id(values.get("target"), "target");
                    case "spawn" -> {
                        if (values.containsKey("target")) {
                            throw invalid("target must be absent for spawn portal destinations");
                        }
                    }
                    default -> throw invalid("type must be server, spawn, or menu");
                }
            }
            case "portal-visualize" -> {
                exact(values, Set.of("operation", "action", "player", "enabled"));
                uuid(values.get("player"));
                bool(values.get("enabled"), "enabled");
            }
            default -> throw invalid("unknown execute action: " + action);
        }
    }

    private static void playerAction(Map<String, Object> values) {
        exact(values, Set.of("operation", "action", "player"));
        uuid(values.get("player"));
    }

    private static void optionalId(Object value, String path) {
        if (value != null) {
            id(value, path);
        }
    }

    private static void optionalPermission(Object value) {
        if (value != null && !text(value, "permission", 128).matches("[A-Za-z0-9._-]{1,128}")) {
            throw invalid("permission must be a permission node");
        }
    }

    private static String file(Object value) {
        String file = text(value, "file", 32);
        if (!ManagedLobbyStore.FILES.contains(file)) {
            throw invalid("file must be one of " + ManagedLobbyStore.FILES);
        }
        return file;
    }

    private static UUID uuid(Object value) {
        String source = text(value, "player", 36);
        try {
            UUID result = UUID.fromString(source);
            if (!result.toString().equals(source)) {
                throw invalid("player must be a canonical UUID");
            }
            return result;
        } catch (IllegalArgumentException exception) {
            throw invalid("player must be a canonical UUID");
        }
    }

    private static void id(Object value, String path) {
        if (!ID.matcher(text(value, path, 64)).matches()) {
            throw invalid(path + " must be a bounded lowercase identifier");
        }
    }

    private static String text(Object value, String path, int maximum) {
        if (!(value instanceof String text) || text.isBlank() || text.length() > maximum) {
            throw invalid(path + " must be bounded non-blank text");
        }
        return text;
    }

    private static void optionalBoolean(Object value, String path) {
        if (value != null) {
            bool(value, path);
        }
    }

    private static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean result)) {
            throw invalid(path + " must be a boolean");
        }
        return result;
    }

    private static void optionalInteger(Object value, String path, int minimum, int maximum) {
        if (value != null) {
            int parsed = integer(value, path);
            if (parsed < minimum || parsed > maximum) {
                throw invalid(path + " is outside the allowed range");
            }
        }
    }

    private static int integer(Object value, String path) {
        if (!(value instanceof Number number) || number.longValue() != number.doubleValue()
                || number.longValue() < Integer.MIN_VALUE || number.longValue() > Integer.MAX_VALUE) {
            throw invalid(path + " must be an integer");
        }
        return number.intValue();
    }

    private static void exact(Map<String, Object> values, Set<String> allowed) {
        for (String key : values.keySet()) {
            if (!allowed.contains(key)) {
                throw invalid("unknown request key: " + key);
            }
        }
    }

    private static void requireAbsentInsteadOfNull(Map<String, Object> values, Set<String> optional) {
        for (String key : optional) {
            if (values.containsKey(key) && values.get(key) == null) {
                throw invalid(key + " must be absent instead of null");
            }
        }
    }

    private static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException("invalid managed lobby request: " + message);
    }
}
