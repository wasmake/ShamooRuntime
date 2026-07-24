package dev.shamoo.runtime.core;

import java.util.concurrent.CompletionStage;

/** Optional engine-neutral contract for transferring opaque state between runtime generations. */
public interface HotStatePluginRuntime extends PluginRuntime {
    CompletionStage<byte[]> exportHotState();

    CompletionStage<Void> importHotState(byte[] state);
}
