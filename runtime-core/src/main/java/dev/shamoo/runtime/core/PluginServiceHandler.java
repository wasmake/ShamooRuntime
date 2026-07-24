package dev.shamoo.runtime.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Engine-neutral implementation of one explicitly versioned plugin service. */
@FunctionalInterface
public interface PluginServiceHandler {
    CompletionStage<Object> invoke(String operation, List<Object> arguments);
}
