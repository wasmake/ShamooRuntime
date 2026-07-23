package dev.shamoo.runtime.platform.paper.nms;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
class PaperNmsCompatibilityTest {
    @Test
    void acceptsOnlyPinnedMinecraftAndPaperBuild() {
        assertDoesNotThrow(() -> PaperNmsCompatibility.requireCompatible("1.21.8", "git-Paper-55 (MC: 1.21.8)"));
        assertDoesNotThrow(() -> PaperNmsCompatibility.requireCompatible(
                "1.21.8", "1.21.8-55-49ca2d2 (MC: 1.21.8)"));
        assertThrows(IllegalStateException.class,
                () -> PaperNmsCompatibility.requireCompatible("1.21.8", "git-Paper-56 (MC: 1.21.8)"));
        assertThrows(IllegalStateException.class,
                () -> PaperNmsCompatibility.requireCompatible("1.21.7", "git-Paper-55 (MC: 1.21.7)"));
    }
}
