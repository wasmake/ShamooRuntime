package dev.shamoo.runtime.codegen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.shamoo.runtime.protocol.ProtocolVersion;
import org.junit.jupiter.api.Test;

class GeneratedBindingTest {
    @Test
    void acceptsCompatibleRuntime() {
        GeneratedBinding binding = new GeneratedBinding("server.api", "Player", new ProtocolVersion(1, 1));

        assertDoesNotThrow(() -> binding.requireCompatible(new ProtocolVersion(1, 2)),
            "newer compatible runtime should accept binding");
    }

    @Test
    void rejectsIncompatibleRuntime() {
        GeneratedBinding binding = new GeneratedBinding("server.api", "Player", new ProtocolVersion(1, 1));

        assertThrows(IllegalArgumentException.class,
            () -> binding.requireCompatible(new ProtocolVersion(2, 0)),
            "different protocol major should be rejected");
    }
}
