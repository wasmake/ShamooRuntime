package dev.shamoo.runtime.bootstrap.paper;

import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.javet.JavetScriptRuntime;
import dev.shamoo.runtime.platform.paper.PaperRuntimeHost;
import java.util.logging.Level;
import org.bukkit.plugin.java.JavaPlugin;

/** Paper entry point that owns the native runtime lifecycle. */
public final class ShamooPaperPlugin extends JavaPlugin {
    private ScriptRuntime runtime;

    @Override
    public void onEnable() {
        try {
            runtime = new JavetScriptRuntime(new PaperRuntimeHost(this));
            if (getLogger().isLoggable(Level.INFO)) {
                getLogger().info("ShamooRuntime initialized with protocol " + runtime.protocolVersion());
            }
        } catch (RuntimeInitializationException exception) {
            if (getLogger().isLoggable(Level.SEVERE)) {
                getLogger().severe("Unable to initialize the V8 runtime: " + exception.getMessage());
            }
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public void onDisable() {
        if (runtime != null) {
            runtime.close();
        }
    }
}
