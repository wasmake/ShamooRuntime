package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.EventContract;
import dev.shamoo.runtime.protocol.SemverRange;
import java.util.concurrent.CompletionStage;

/** Generation-scoped cross-plugin versioned event surface. */
public interface PluginEvents {
    AutoCloseable subscribe(String eventName, SemverRange versions, PluginEventHandler handler);

    CompletionStage<Void> publish(EventContract contract, Object payload);
}
