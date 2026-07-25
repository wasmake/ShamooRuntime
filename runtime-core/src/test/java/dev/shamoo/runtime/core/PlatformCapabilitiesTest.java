package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.AvoidDuplicateLiterals"
})
class PlatformCapabilitiesTest {
    private static final Map<String, Object> PAPER_BINDING = Map.of(
            "namespace", "paper", "typeName", "subscribe", "componentId", "src/listeners.ts#JoinListener",
            "method", "onJoin",
            "protocolMajor", 1, "protocolMinor", 0);

    @Test
    void cachesImmutableBindingIdentityWithoutCapturingInvocationOwner() throws Exception {
        PlatformCapabilities capabilities = new PlatformCapabilities("paper", Map.of("subscribe",
                (owner, metadata, arguments) -> owner.value() + ':' + metadata.method()));

        assertEquals("first:onJoin",
                capabilities.invoke("subscribe", new PluginId("first"), PAPER_BINDING, List.of()));
        assertEquals("second:onJoin",
                capabilities.invoke("subscribe", new PluginId("second"), Map.copyOf(PAPER_BINDING), List.of()));
        assertEquals(1, capabilities.cachedAdapterCount());
    }

    @Test
    void rejectsCrossPlatformGeneratedMetadataAtEveryInvocation() {
        PlatformCapabilities capabilities = new PlatformCapabilities("paper", Map.of("subscribe",
                (owner, metadata, arguments) -> null));
        Map<String, Object> velocity = new java.util.HashMap<>(PAPER_BINDING);
        velocity.put("namespace", "velocity");

        assertThrows(IllegalArgumentException.class,
                () -> capabilities.invoke("subscribe", new PluginId("plugin"), velocity, List.of()));
        assertEquals(0, capabilities.cachedAdapterCount());
    }

    @Test
    void requiresExactOperationAndGeneratedMethodIdentity() throws Exception {
        PlatformCapabilities capabilities = new PlatformCapabilities("paper", Map.of("subscribe",
                (owner, metadata, arguments) -> metadata.componentId()));
        Map<String, Object> wrongType = new java.util.HashMap<>(PAPER_BINDING);
        wrongType.put("typeName", "otherOperation");
        Map<String, Object> otherMethod = new java.util.HashMap<>(PAPER_BINDING);
        otherMethod.put("method", "onQuit");
        Map<String, Object> missingMethod = new java.util.HashMap<>(PAPER_BINDING);
        missingMethod.remove("method");

        assertThrows(IllegalArgumentException.class,
                () -> capabilities.invoke("subscribe", new PluginId("plugin"), wrongType, List.of()));
        capabilities.invoke("subscribe", new PluginId("plugin"), PAPER_BINDING, List.of());
        capabilities.invoke("subscribe", new PluginId("plugin"), otherMethod, List.of());
        assertEquals(2, capabilities.cachedAdapterCount());
        assertThrows(IllegalArgumentException.class,
                () -> capabilities.invoke("subscribe", new PluginId("plugin"), missingMethod, List.of()));
    }

}
