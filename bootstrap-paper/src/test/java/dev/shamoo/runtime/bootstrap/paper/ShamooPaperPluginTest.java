package dev.shamoo.runtime.bootstrap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Method;
import java.util.Map;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.AvoidAccessibilityAlteration")
class ShamooPaperPluginTest {
    @Test
    void normalizesBukkitBuildSuffixes() throws ReflectiveOperationException {
        Map<String, String> versions = Map.of(
                "26.2.build.65", "26.2",
                "26.2.1.build.65", "26.2.1",
                "26.2", "26.2",
                "26.2.1", "26.2.1",
                "1.21.build.12", "1.21",
                "1.21.8.build.12", "1.21.8");

        Method normalizer = ShamooPaperPlugin.class.getDeclaredMethod("normalizeMinecraftVersion", String.class);
        normalizer.setAccessible(true);
        for (Map.Entry<String, String> version : versions.entrySet()) {
            assertEquals(version.getValue(), normalizer.invoke(null, version.getKey()), version.getKey());
        }
    }
}
