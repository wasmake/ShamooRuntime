package dev.shamoo.runtime.core;

import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** A synchronous host suitable for command-line tools and deterministic tests. */
public final class DirectRuntimeHost implements RuntimeHost {
    private final String name;
    private final System.Logger systemLogger;

    public DirectRuntimeHost(String platformName, System.Logger logger) {
        if (platformName == null || platformName.isBlank()) {
            throw new IllegalArgumentException("platformName must not be blank");
        }
        this.name = platformName;
        this.systemLogger = Objects.requireNonNull(logger, "logger");
    }

    @Override
    public String platformName() {
        return name;
    }

    @Override
    public System.Logger logger() {
        return systemLogger;
    }

    @Override
    public CompletionStage<Void> dispatch(Runnable task) {
        Objects.requireNonNull(task, "task").run();
        return CompletableFuture.completedFuture(null);
    }
}
