package dev.shamoo.runtime.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic registry of exact generated-to-original source positions. */
public final class SourceMapRegistry {
    private static final java.util.regex.Pattern STACK_POSITION = java.util.regex.Pattern.compile(
            "(plugin:/[^\\s():]+):(\\d+):(\\d+)");
    private final Map<SourcePosition, SourcePosition> positions = new ConcurrentHashMap<>();

    public void register(SourcePosition generated, SourcePosition original) {
        positions.put(Objects.requireNonNull(generated, "generated"), Objects.requireNonNull(original, "original"));
    }

    public SourcePosition map(SourcePosition generated) {
        SourcePosition exact = positions.get(generated);
        if (exact != null) {
            return exact;
        }
        Map.Entry<SourcePosition, SourcePosition> nearest = positions.entrySet().stream()
                .filter(entry -> entry.getKey().resourceName().equals(generated.resourceName())
                        && entry.getKey().line() == generated.line()
                        && entry.getKey().column() <= generated.column())
                .max(java.util.Comparator.comparingInt(entry -> entry.getKey().column())).orElse(null);
        if (nearest == null) {
            return generated;
        }
        SourcePosition original = nearest.getValue();
        return new SourcePosition(original.resourceName(), original.line(),
                original.column() + generated.column() - nearest.getKey().column());
    }

    /** Rewrites every exact generated frame while preserving non-position stack text. */
    public String mapStack(String stack) {
        if (stack == null || stack.isEmpty()) {
            return stack;
        }
        java.util.regex.Matcher matcher = STACK_POSITION.matcher(stack);
        StringBuilder mapped = new StringBuilder(stack.length());
        while (matcher.find()) {
            SourcePosition original = map(new SourcePosition(matcher.group(1),
                    Integer.parseInt(matcher.group(2)), Integer.parseInt(matcher.group(3))));
            matcher.appendReplacement(mapped, java.util.regex.Matcher.quoteReplacement(
                    original.resourceName() + ":" + original.line() + ":" + original.column()));
        }
        matcher.appendTail(mapped);
        return mapped.toString();
    }

    public void clear() {
        positions.clear();
    }

    public int size() {
        return positions.size();
    }
}
