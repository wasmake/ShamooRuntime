package dev.shamoo.runtime.core;

import java.util.concurrent.CompletionStage;

/** Engine-neutral lifecycle hooks for one plugin runtime. No implementation types cross this boundary. */
public interface PluginRuntime {
    CompletionStage<Void> load();

    CompletionStage<Void> enable();

    CompletionStage<Void> ready();

    CompletionStage<Void> drain();

    CompletionStage<Void> disable();

    CompletionStage<Void> unload();
}
