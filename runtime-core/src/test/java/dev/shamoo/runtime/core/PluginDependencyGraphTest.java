package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.AvoidDuplicateLiterals"
})
class PluginDependencyGraphTest {
    private final PluginDependencyGraph graph = new PluginDependencyGraph();

    @Test
    void ordersRequiredOptionalAndHintsDeterministicallyAndReversesDisable() {
        InstalledPluginCandidate alpha = TestCandidates.candidate("alpha");
        InstalledPluginCandidate beta = TestCandidates.candidate("beta", "1.2.0", """
                {"required":{"alpha":"^1.0.0"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        InstalledPluginCandidate gamma = TestCandidates.candidate("gamma", "1.0.0", """
                {"required":{},"optional":{"beta":"1.x"},"loadBefore":[],"loadAfter":[]}
                """);
        DependencyResolution resolution = graph.resolve(List.of(gamma, beta, alpha));
        assertEquals(List.of(new PluginId("alpha"), new PluginId("beta"), new PluginId("gamma")),
                resolution.enableOrder());
        assertEquals(List.of(new PluginId("gamma"), new PluginId("beta"), new PluginId("alpha")),
                resolution.disableOrder());
    }

    @Test
    void reportsCompleteCanonicalCyclePaths() {
        InstalledPluginCandidate alpha = TestCandidates.candidate("alpha", "1.0.0", """
                {"required":{"bravo":"*"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        InstalledPluginCandidate bravo = TestCandidates.candidate("bravo", "1.0.0", """
                {"required":{"charlie":"*"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        InstalledPluginCandidate charlie = TestCandidates.candidate("charlie", "1.0.0", """
                {"required":{"alpha":"*"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        DependencyResolution resolution = graph.resolve(List.of(charlie, alpha, bravo));
        assertEquals(List.of(new PluginId("alpha"), new PluginId("bravo"),
                new PluginId("charlie"), new PluginId("alpha")), resolution.cycles().getFirst());
        assertEquals(3, resolution.blocked().size());
    }

    @Test
    void blocksMissingAndIncompatibleRequiredDependenciesButIgnoresOptionalMismatch() {
        InstalledPluginCandidate base = TestCandidates.candidate("base", "2.0.0", TestCandidates.emptyDependencies());
        InstalledPluginCandidate missing = TestCandidates.candidate("missing", "1.0.0", """
                {"required":{"absent":"*"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        InstalledPluginCandidate incompatible = TestCandidates.candidate("incompatible", "1.0.0", """
                {"required":{"base":"1.x"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        InstalledPluginCandidate optional = TestCandidates.candidate("optional", "1.0.0", """
                {"required":{},"optional":{"base":"1.x"},"loadBefore":[],"loadAfter":[]}
                """);
        DependencyResolution resolution = graph.resolve(List.of(missing, incompatible, optional, base));
        assertEquals("required_dependency_missing",
                resolution.blocked().get(new PluginId("missing")).getFirst().code());
        assertEquals("required_dependency_incompatible",
                resolution.blocked().get(new PluginId("incompatible")).getFirst().code());
        assertFalse(resolution.blocked().containsKey(new PluginId("optional")));
        assertTrue(resolution.enableOrder().contains(new PluginId("optional")));
    }

    @Test
    void reevaluationUnblocksDependentsWhenCompatibilityAppears() {
        InstalledPluginCandidate dependent = TestCandidates.candidate("dependent", "1.0.0", """
                {"required":{"base":"^1.0.0"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        assertTrue(graph.resolve(List.of(dependent)).blocked().containsKey(dependent.pluginId()));
        DependencyResolution restored = graph.reevaluate(List.of(dependent, TestCandidates.candidate("base")));
        assertFalse(restored.blocked().containsKey(dependent.pluginId()));
    }
}
