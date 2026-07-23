package dev.shamoo.runtime.core;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** Deterministic registry of exact generated-to-original source positions. */
public final class SourceMapRegistry {
    private final Map<SourcePosition, SourcePosition> positions = new ConcurrentHashMap<>();

    public void register(SourcePosition generated, SourcePosition original) {
        positions.put(Objects.requireNonNull(generated, "generated"), Objects.requireNonNull(original, "original"));
    }

    public SourcePosition map(SourcePosition generated) {
        return positions.getOrDefault(generated, generated);
    }

    public void clear() {
        positions.clear();
    }

    public int size() {
        return positions.size();
    }
}
