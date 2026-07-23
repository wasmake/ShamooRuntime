package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.shamoo.runtime.protocol.CompatibilityInput;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.SemanticVersion;
import java.util.Set;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class ManifestValidatorTest {
    @Test
    void exposesNegotiationFailureAtCoreAdmissionBoundary() {
        String json = """
                {"name":"sample","displayName":"Sample","version":"1.0.0",
                "shamoo":{"api":"^1.0.0","runtime":"^1.0.0","manifest":1},
                "platforms":{"paper":{"enabled":true,"entrypoint":"index.js","minecraft":"1.21.x",
                "paperApi":"1.21.x"},"velocity":{"enabled":false}},
                "dependencies":{"required":{},"optional":{},"loadBefore":[],"loadAfter":[]},
                "node":{"builtins":[],"filesystem":{"read":[],"write":[]},"network":false,
                "workers":false,"childProcess":false,"nativeAddons":false},
                "reload":{"watch":false,"debounceMs":0,"preserveState":false}}
                """;
        CompatibilityInput velocity = new CompatibilityInput(
                PlatformKind.VELOCITY, null, null, version("3.4.0"), Set.of(),
                version("1.0.0"), version("1.0.0"), ProtocolVersion.CURRENT);

        IncompatibleManifestException exception = assertThrows(IncompatibleManifestException.class,
                () -> new ManifestValidator().parseCompatible(json, velocity));

        assertEquals("platform_target", exception.result().reasons().getFirst().code());
    }

    private static SemanticVersion version(String value) {
        return new SemanticVersion(value);
    }
}
