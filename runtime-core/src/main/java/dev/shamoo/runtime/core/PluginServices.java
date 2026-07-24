package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.SemverRange;
import dev.shamoo.runtime.protocol.ServiceContract;

/** Generation-scoped cross-plugin service registration and lookup surface. */
public interface PluginServices {
    AutoCloseable provide(ServiceContract contract, PluginServiceHandler handler);

    PluginServiceProxy acquire(String serviceName, SemverRange versions, DependentReloadPolicy reloadPolicy);
}
