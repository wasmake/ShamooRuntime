package dev.shamoo.runtime.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Stable consumer handle that resolves its provider generation for every invocation. */
public interface PluginServiceProxy extends AutoCloseable {
    CompletionStage<Object> invoke(String operation, List<Object> arguments);

    @Override
    void close();
}
