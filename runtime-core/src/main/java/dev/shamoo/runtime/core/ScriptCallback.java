package dev.shamoo.runtime.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Opaque allowlisted JS callback; platform adapters may pass only copied data values. */
@FunctionalInterface
public interface ScriptCallback extends AutoCloseable {
    CompletionStage<Object> invoke(List<Object> arguments);

    @Override
    default void close() {
        // Stateless/test callbacks do not need cleanup.
    }
}
