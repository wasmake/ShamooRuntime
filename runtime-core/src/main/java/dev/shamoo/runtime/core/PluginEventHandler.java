package dev.shamoo.runtime.core;

import java.util.concurrent.CompletionStage;

/** Engine-neutral subscriber for a compatible versioned event payload. */
@FunctionalInterface
public interface PluginEventHandler {
    CompletionStage<Void> handle(Object payload);
}
