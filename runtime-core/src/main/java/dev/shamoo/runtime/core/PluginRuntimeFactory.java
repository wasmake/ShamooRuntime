package dev.shamoo.runtime.core;

import java.util.concurrent.CompletionStage;

/** Composition-root interface used to create an engine runtime for an admitted candidate. */
@FunctionalInterface
public interface PluginRuntimeFactory {
    CompletionStage<PluginRuntime> create(PluginRuntimeContext context);
}
