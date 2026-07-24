package dev.shamoo.runtime.protocol;

import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

final class ManifestValidation {
    static final int MAX_TEXT_LENGTH = 256;
    static final int MAX_PATH_LENGTH = 512;
    static final int MAX_COLLECTION_SIZE = 256;
    private static final Pattern PLUGIN_ID = Pattern.compile("[a-z][a-z0-9]*(?:[._-][a-z0-9]+)*");
    private static final Pattern BUILTIN = Pattern.compile("(?:node:)?[a-z][a-z0-9_/-]*");

    private ManifestValidation() {
    }

    static String text(String value, String path) {
        if (value == null || value.isBlank()) {
            fail("invalid_value", path, "must not be blank");
        }
        if (value.codePointCount(0, value.length()) > MAX_TEXT_LENGTH) {
            fail("invalid_value", path, "must be at most " + MAX_TEXT_LENGTH + " characters");
        }
        return value;
    }

    static String pluginId(String value, String path) {
        text(value, path);
        if (!PLUGIN_ID.matcher(value).matches() || value.length() > 64) {
            fail("invalid_plugin_id", path, "must match " + PLUGIN_ID.pattern() + " and be at most 64 characters");
        }
        return value;
    }

    static String relativePath(String value, String path) {
        text(value, path);
        if (value.length() > MAX_PATH_LENGTH) {
            fail("unsafe_path", path, "must be at most " + MAX_PATH_LENGTH + " characters");
        }
        boolean windowsDrive = value.length() >= 2 && Character.isLetter(value.charAt(0)) && value.charAt(1) == ':';
        if (value.startsWith("/") || value.startsWith("\\") || value.contains("\\") || value.indexOf('\0') >= 0
                || windowsDrive) {
            fail("unsafe_path", path, "must be a forward-slash relative path");
        }
        String normalized = value.startsWith("./") ? value.substring(2) : value;
        if (normalized.isEmpty()) {
            return value;
        }
        for (String segment : normalized.split("/", -1)) {
            if (segment.isEmpty() || ".".equals(segment) || "..".equals(segment)) {
                fail("unsafe_path", path, "must not contain empty or traversal segments");
            }
        }
        return value;
    }

    static String entrypoint(String value, String path) {
        relativePath(value, path);
        if ("./".equals(value) || !(value.endsWith(".js") || value.endsWith(".mjs") || value.endsWith(".cjs"))) {
            fail("invalid_entrypoint", path, "must be a safe relative .js, .mjs, or .cjs file");
        }
        return value;
    }

    static void required(Object value, String path) {
        if (value == null) {
            fail("missing_field", path, "required manifest field is missing");
        }
    }

    static List<String> paths(List<String> values, String path) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, path));
        collectionSize(copy.size(), path);
        for (int index = 0; index < copy.size(); index++) {
            relativePath(copy.get(index), path + "/" + index);
        }
        unique(copy, path);
        return copy;
    }

    static List<String> pluginIds(List<String> values, String path) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, path));
        collectionSize(copy.size(), path);
        for (int index = 0; index < copy.size(); index++) {
            pluginId(copy.get(index), path + "/" + index);
        }
        unique(copy, path);
        return copy;
    }

    static List<String> builtins(List<String> values, String path) {
        List<String> copy = List.copyOf(Objects.requireNonNull(values, path));
        collectionSize(copy.size(), path);
        for (int index = 0; index < copy.size(); index++) {
            String value = text(copy.get(index), path + "/" + index);
            if (!BUILTIN.matcher(value).matches() || value.contains("..")) {
                fail("invalid_builtin", path + "/" + index, "is not a valid Node builtin module name");
            }
        }
        unique(copy, path);
        return copy;
    }

    static Map<String, SemverRange> dependencies(Map<String, SemverRange> values, String path) {
        Map<String, SemverRange> copy = Map.copyOf(Objects.requireNonNull(values, path));
        collectionSize(copy.size(), path);
        copy.forEach((id, range) -> {
            pluginId(id, path + "/" + id);
            Objects.requireNonNull(range, path + "/" + id);
        });
        return copy;
    }

    static void disjoint(Set<String> left, Set<String> right, String path, String message) {
        Set<String> overlap = new HashSet<>(left);
        overlap.retainAll(right);
        if (!overlap.isEmpty()) {
            fail("conflicting_entries", path, message + ": " + overlap);
        }
    }

    static void fail(String code, String path, String message) {
        throw new ManifestValidationException(new ProtocolDiagnostic(code, path, message));
    }

    private static void unique(List<String> values, String path) {
        if (new HashSet<>(values).size() != values.size()) {
            fail("duplicate_entry", path, "must not contain duplicate entries");
        }
    }

    private static void collectionSize(int size, String path) {
        if (size > MAX_COLLECTION_SIZE) {
            fail("too_many_entries", path, "must contain at most " + MAX_COLLECTION_SIZE + " entries");
        }
    }
}
