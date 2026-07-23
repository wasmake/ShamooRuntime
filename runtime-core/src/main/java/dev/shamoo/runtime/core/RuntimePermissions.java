package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.NodePolicy;
import java.util.Set;

/** Canonical immutable runtime permissions derived from a manifest Node policy. */
public record RuntimePermissions(
        Set<String> builtins,
        Set<String> readablePaths,
        Set<String> writablePaths,
        boolean network,
        boolean workers,
        boolean childProcess,
        boolean nativeAddons) {
    private static final String NODE_PREFIX = "node:";

    public RuntimePermissions {
        builtins = Set.copyOf(builtins);
        readablePaths = Set.copyOf(readablePaths);
        writablePaths = Set.copyOf(writablePaths);
    }

    /** Converts aliases such as {@code path} and {@code node:path} to one canonical name. */
    public static RuntimePermissions from(NodePolicy policy) {
        Set<String> canonicalBuiltins = policy.builtins().stream()
            .map(name -> name.startsWith(NODE_PREFIX) ? name : NODE_PREFIX + name)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new RuntimePermissions(
            canonicalBuiltins,
            Set.copyOf(policy.filesystem().read()),
            Set.copyOf(policy.filesystem().write()),
            policy.network(),
            policy.workers(),
            policy.childProcess(),
            policy.nativeAddons());
    }

    public boolean permitsBuiltin(String name) {
        String canonical = name.startsWith(NODE_PREFIX) ? name : NODE_PREFIX + name;
        return builtins.contains(canonical);
    }
}
