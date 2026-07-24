package dev.shamoo.runtime.bootstrap.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.javet.HostedPluginStatus;
import java.util.List;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class VelocityRuntimeCommandsTest {
    @Test
    void listsActiveShamooPluginsFirstWithStatusColors() {
        TextComponent message = (TextComponent) VelocityRuntimeCommands.shamooPlugins(List.of(
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
