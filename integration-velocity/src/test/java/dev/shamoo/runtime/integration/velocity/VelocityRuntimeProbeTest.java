package dev.shamoo.runtime.integration.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;

import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.ScriptRequest;
import dev.shamoo.runtime.protocol.ScriptResult;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import org.junit.jupiter.api.Test;

class VelocityRuntimeProbeTest {
    @Test
    void tagsRequestsForVelocity() {
        try (CapturingRuntime runtime = new CapturingRuntime()) {
            new VelocityRuntimeProbe(runtime).evaluate("probe", "40 + 2").toCompletableFuture().join();

            assertEquals("velocity", runtime.request.attributes().get("platform"), "platform attribute");
        }
    }

    private static final class CapturingRuntime implements ScriptRuntime {
        private ScriptRequest request;

        @Override
        public ProtocolVersion protocolVersion() {
            return ProtocolVersion.CURRENT;
        }

        @Override
        public CompletionStage<ScriptResult> execute(ScriptRequest request) {
            this.request = request;
            return CompletableFuture.completedFuture(ScriptResult.success(request.requestId(), "42", Duration.ZERO));
        }

        @Override
        public boolean isClosed() {
            return false;
        }

        @Override
        public void close() {
        }
    }
}
