package dev.shamoo.runtime.bootstrap.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.event.proxy.ProxyShutdownEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.ProxyServer;
import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.javet.JavetScriptRuntime;
import dev.shamoo.runtime.platform.velocity.VelocityRuntimeHost;

/** Velocity entry point that owns the native runtime lifecycle. */
@Plugin(
    id = "shamooruntime",
    name = "ShamooRuntime",
    version = "0.1.0-SNAPSHOT",
    description = "JavaScript runtime foundation for Velocity"
)
public final class ShamooVelocityPlugin {
    private final ProxyServer server;
    private ScriptRuntime runtime;

    @Inject
    public ShamooVelocityPlugin(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    public void onProxyInitialization(ProxyInitializeEvent event) throws RuntimeInitializationException {
        runtime = new JavetScriptRuntime(new VelocityRuntimeHost(server, this));
        System.getLogger(getClass().getName()).log(
            System.Logger.Level.INFO, "ShamooRuntime initialized with protocol " + runtime.protocolVersion());
    }

    @Subscribe
    public void onProxyShutdown(ProxyShutdownEvent event) {
        if (runtime != null) {
            runtime.close();
        }
    }
}
