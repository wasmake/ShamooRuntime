package dev.shamoo.runtime.core;

import java.util.List;
import java.util.concurrent.CompletionStage;

/** Opaque allowlisted JS callback; platform adapters may pass only copied data values. */
@FunctionalInterface
public interface ScriptCallback {
    CompletionStage<Object> invoke(List<Object> arguments);
}
