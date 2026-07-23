package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ProtocolVersionTest {
    @Test
    void acceptsOlderMinorVersion() {
        ProtocolVersion runtime = new ProtocolVersion(1, 2);

        assertTrue(runtime.isCompatibleWith(new ProtocolVersion(1, 1)), "older minor should be accepted");
    }

    @Test
    void rejectsNewerMinorVersion() {
        ProtocolVersion runtime = new ProtocolVersion(1, 2);

        assertFalse(runtime.isCompatibleWith(new ProtocolVersion(1, 3)), "newer minor should be rejected");
    }

    @Test
    void rejectsDifferentMajorVersion() {
        ProtocolVersion runtime = new ProtocolVersion(1, 2);

        assertFalse(runtime.isCompatibleWith(new ProtocolVersion(2, 0)), "different major should be rejected");
    }
}
