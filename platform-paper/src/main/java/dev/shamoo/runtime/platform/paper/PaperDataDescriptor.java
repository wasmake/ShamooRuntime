package dev.shamoo.runtime.platform.paper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Strict helpers for platform descriptors received from a script runtime. */
final class PaperDataDescriptor {
    private PaperDataDescriptor() {
    }

    static Map<String, Object> object(
            Object value, String path, Set<String> allowed, Set<String> required) {
        if (!(value instanceof Map<?, ?> source)) {
            throw invalid(path, "must be an object");
        }
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, item) -> {
            if (!(key instanceof String name) || !allowed.contains(name)) {
                throw invalid(path, "contains an unknown key: " + key);
            }
            result.put(name, item);
        });
        for (String name : required) {
            if (!result.containsKey(name) || result.get(name) == null) {
                throw invalid(path + "." + name, "is required");
            }
        }
        return result;
    }

    static List<Object> array(Object value, String path) {
        if (!(value instanceof List<?> source)) {
            throw invalid(path, "must be an array");
        }
        return new ArrayList<>(source);
    }

    static List<String> strings(Object value, String path) {
        List<Object> source = array(value, path);
        List<String> result = new ArrayList<>(source.size());
        for (int index = 0; index < source.size(); index++) {
            result.add(text(source.get(index), path + '[' + index + ']', false));
        }
        return List.copyOf(result);
    }

    static String text(Object value, String path, boolean allowEmpty) {
        if (!(value instanceof String text) || text.length() > 32_767 || (!allowEmpty && text.isBlank())) {
            throw invalid(path, "must be " + (allowEmpty ? "bounded text" : "bounded non-blank text"));
        }
        return text;
    }

    static boolean bool(Object value, String path) {
        if (!(value instanceof Boolean result)) {
            throw invalid(path, "must be a boolean");
        }
        return result;
    }

    static int integer(Object value, String path) {
        if (!(value instanceof Number number)) {
            throw invalid(path, "must be an integer");
        }
        long result = number.longValue();
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE || number.doubleValue() != result) {
            throw invalid(path, "must be an integer");
        }
        return (int) result;
    }

    static IllegalArgumentException invalid(String path, String message) {
        return new IllegalArgumentException("invalid " + path + ": " + message);
    }
}
