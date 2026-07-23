package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.ScriptRequest;
import dev.shamoo.runtime.protocol.ScriptResult;
import java.util.concurrent.CompletionStage;

/** A lifecycle-bound script engine. Implementations must reject work after close. */
public interface ScriptRuntime extends AutoCloseable {
    ProtocolVersion protocolVersion();

    CompletionStage<ScriptResult> execute(ScriptRequest request);

    boolean isClosed();

    @Override
    void close();
}
