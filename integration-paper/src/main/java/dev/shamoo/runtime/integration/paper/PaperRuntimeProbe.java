package dev.shamoo.runtime.integration.paper;

import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.protocol.ScriptRequest;
import dev.shamoo.runtime.protocol.ScriptResult;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;

/** Reusable smoke probe for a runtime installed in a Paper test server. */
public final class PaperRuntimeProbe {
    private final ScriptRuntime runtime;

    public PaperRuntimeProbe(ScriptRuntime runtime) {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
    }

    public CompletionStage<ScriptResult> evaluate(String requestId, String expression) {
        return runtime.execute(new ScriptRequest(requestId, expression, Map.of("platform", "paper")));
    }
}
