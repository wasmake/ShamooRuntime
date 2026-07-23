package dev.shamoo.runtime.core;

import java.util.List;

/** Result of a reverse-order resource cleanup pass. */
public record ResourceCleanupReport(
        PluginId owner,
        int attempted,
        int cleaned,
        List<ResourceRegistration> leaked,
        List<Exception> errors) {
    public ResourceCleanupReport {
        leaked = List.copyOf(leaked);
        errors = List.copyOf(errors);
    }

    public boolean clean() {
        return leaked.isEmpty() && errors.isEmpty();
    }
}
