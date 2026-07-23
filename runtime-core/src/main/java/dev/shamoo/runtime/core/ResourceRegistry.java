package dev.shamoo.runtime.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Thread-safe typed ownership registry that closes resources in reverse registration order. */
public final class ResourceRegistry {
    static final PluginId RUNTIME_OWNER = new PluginId("runtime");
    private final Deque<OwnedResource> resources = new ArrayDeque<>();

    public synchronized <T extends AutoCloseable> T register(T resource) {
        return register(RUNTIME_OWNER, ResourceCategory.GENERIC,
                Objects.requireNonNull(resource, "resource").getClass().getName(), resource);
    }

    public synchronized <T extends AutoCloseable> T register(
            PluginId owner, ResourceCategory category, String description, T resource) {
        ResourceRegistration registration = new ResourceRegistration(
                UUID.randomUUID(), owner, category, description, Instant.now());
        resources.push(new OwnedResource(registration, Objects.requireNonNull(resource, "resource")));
        return resource;
    }

    public synchronized int size() {
        return resources.size();
    }

    public synchronized List<ResourceRegistration> snapshot() {
        return resources.stream().map(OwnedResource::registration).toList();
    }

    public synchronized List<ResourceRegistration> snapshot(PluginId owner) {
        Objects.requireNonNull(owner, "owner");
        return resources.stream().map(OwnedResource::registration)
                .filter(registration -> registration.owner().equals(owner)).toList();
    }

    public synchronized ResourceCleanupReport cleanup(PluginId owner) {
        Objects.requireNonNull(owner, "owner");
        List<OwnedResource> retained = new ArrayList<>();
        List<Exception> errors = new ArrayList<>();
        int attempted = 0;
        int cleaned = 0;
        while (!resources.isEmpty()) {
            OwnedResource owned = resources.pop();
            if (!owned.registration().owner().equals(owner)) {
                retained.add(owned);
                continue;
            }
            attempted++;
            try {
                owned.resource().close();
                cleaned++;
            } catch (Exception exception) {
                errors.add(exception);
                retained.add(owned);
            }
        }
        for (int index = retained.size() - 1; index >= 0; index--) {
            resources.push(retained.get(index));
        }
        List<ResourceRegistration> leaked = snapshot(owner);
        return new ResourceCleanupReport(owner, attempted, cleaned, leaked, errors);
    }

    public synchronized void closeAll() throws Exception {
        Exception failure = null;
        while (!resources.isEmpty()) {
            try {
                resources.pop().resource().close();
            } catch (Exception exception) {
                if (failure == null) {
                    failure = exception;
                } else {
                    failure.addSuppressed(exception);
                }
            }
        }
        if (failure != null) {
            throw failure;
        }
    }

    private record OwnedResource(ResourceRegistration registration, AutoCloseable resource) {
    }
}
