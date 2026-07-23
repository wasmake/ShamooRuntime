package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.HashMap;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

class PaperCommandBridgeTest {
    @Test
    void fallsBackWhenLifecycleCannotRemoveImmediately() {
        assertEquals(PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK,
                PaperCommandBridge.selectCapability(true), "commands must support immediate label removal");
    }

    @Test
    void selectsFallbackOnlyWhenUnavailable() {
        assertEquals(PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK,
                PaperCommandBridge.selectCapability(false), "legacy capability must be selected explicitly");
    }

    @Test
    void closeRemovesEveryKnownCommandLabel() {
        Command command = new Command("primary") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] arguments) {
                return true;
            }
        };
        var known = new HashMap<String, Command>();
        known.put("primary", command);
        known.put("plugin:primary", command);

        PaperCommandBridge.removeKnownCommands(known, command);

        assertFalse(known.containsValue(command), "no command label may remain after close");
    }
}
