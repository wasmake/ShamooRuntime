package dev.shamoo.runtime.core;

import java.util.Objects;

/** Stable reason that prevents a plugin from loading. */
public record DependencyBlock(String code, PluginId dependency, String detail) {
    public DependencyBlock {
        code = Objects.requireNonNull(code, "code");
        Objects.requireNonNull(dependency, "dependency");
        detail = Objects.requireNonNull(detail, "detail");
    }
}
