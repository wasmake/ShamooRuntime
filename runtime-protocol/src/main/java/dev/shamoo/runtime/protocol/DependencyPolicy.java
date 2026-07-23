package dev.shamoo.runtime.protocol;

import java.util.Map;
import java.util.Set;

/** Plugin dependency constraints and deterministic relative ordering hints. */
public record DependencyPolicy(
        Map<String, SemverRange> required,
        Map<String, SemverRange> optional,
        java.util.List<String> loadBefore,
        java.util.List<String> loadAfter) {
    public DependencyPolicy {
        required = ManifestValidation.dependencies(required, "/dependencies/required");
        optional = ManifestValidation.dependencies(optional, "/dependencies/optional");
        loadBefore = ManifestValidation.pluginIds(loadBefore, "/dependencies/loadBefore");
        loadAfter = ManifestValidation.pluginIds(loadAfter, "/dependencies/loadAfter");
        ManifestValidation.disjoint(required.keySet(), optional.keySet(), "/dependencies",
                "a dependency cannot be both required and optional");
        ManifestValidation.disjoint(Set.copyOf(loadBefore), Set.copyOf(loadAfter), "/dependencies",
                "a plugin cannot appear in both loadBefore and loadAfter");
    }

    @Override
    public Map<String, SemverRange> required() {
        return Map.copyOf(required);
    }

    @Override
    public Map<String, SemverRange> optional() {
        return Map.copyOf(optional);
    }

    @Override
    public java.util.List<String> loadBefore() {
        return java.util.List.copyOf(loadBefore);
    }

    @Override
    public java.util.List<String> loadAfter() {
        return java.util.List.copyOf(loadAfter);
    }
}
