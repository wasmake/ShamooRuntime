package dev.shamoo.runtime.bootstrap.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.javet.HostedPluginStatus;
import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidAccessibilityAlteration", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.UnitTestAssertionsShouldIncludeMessage"})
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

    @Test
    void listsActiveShamooPluginsFirstWithStatusColors() {
        TextComponent message = (TextComponent) PaperRuntimeCommands.shamooPlugins(List.of(
                new HostedPluginStatus(new PluginId("inactive"), false),
                new HostedPluginStatus(new PluginId("active"), true)));

        TextComponent active = (TextComponent) message.children().get(1);
        TextComponent inactive = (TextComponent) message.children().get(3);
        assertEquals("active", active.content());
        assertEquals(NamedTextColor.GREEN, active.color());
        assertEquals("inactive", inactive.content());
        assertEquals(NamedTextColor.GRAY, inactive.color());
    }

}
