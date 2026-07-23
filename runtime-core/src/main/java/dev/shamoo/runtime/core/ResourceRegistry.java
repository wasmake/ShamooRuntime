package dev.shamoo.runtime.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;

/** Owner-thread registry that closes native and host resources in reverse registration order. */
public final class ResourceRegistry {
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();

    public synchronized <T extends AutoCloseable> T register(T resource) {
        resources.push(Objects.requireNonNull(resource, "resource"));
        return resource;
    }

    public synchronized int size() {
        return resources.size();
    }

    public synchronized void closeAll() throws Exception {
        Exception failure = null;
        while (!resources.isEmpty()) {
            try {
                resources.pop().close();
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
}
