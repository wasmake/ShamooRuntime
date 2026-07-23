package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.shamoo.runtime.core.DirectRuntimeHost;
import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.protocol.ScriptRequest;
import java.util.Map;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

class JavetScriptRuntimeTest {
    @Test
    void evaluatesJavaScriptOnV8() throws RuntimeInitializationException {
        DirectRuntimeHost host = new DirectRuntimeHost("test", System.getLogger(getClass().getName()));
        try (JavetScriptRuntime runtime = new JavetScriptRuntime(host)) {
            String value = runtime.execute(new ScriptRequest("arithmetic", "6 * 7", Map.of()))
                .toCompletableFuture().join().value();

            assertEquals("42", value, "V8 expression result");
        }
    }

    @Test
    @SuppressWarnings("try")
    void rejectsExecutionAfterClose() throws RuntimeInitializationException {
        DirectRuntimeHost host = new DirectRuntimeHost("test", System.getLogger(getClass().getName()));
        try (JavetScriptRuntime runtime = new JavetScriptRuntime(host)) {
            runtime.close();

            assertThrows(CompletionException.class,
                () -> runtime.execute(new ScriptRequest("closed", "1", Map.of())).toCompletableFuture().join(),
                "closed runtime must reject work");
        }
    }
}
