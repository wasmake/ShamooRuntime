package dev.shamoo.runtime.javet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import dev.shamoo.runtime.core.DirectRuntimeHost;
import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.protocol.ScriptRequest;
import dev.shamoo.runtime.protocol.ScriptResult;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts"})
class JavetScriptRuntimeTest {
    private final DirectRuntimeHost host =
        new DirectRuntimeHost("test", System.getLogger(getClass().getName()));

    @Test
    void evaluatesJavaScriptOnV8() throws RuntimeInitializationException {
        try (JavetScriptRuntime runtime = new JavetScriptRuntime(host)) {
            ScriptResult result = execute(runtime, "arithmetic", "6 * 7").join();

            assertEquals("42", result.value());
        }
    }

    @Test
    void convertsOnlyEvaluationErrorsToFailureResults() throws RuntimeInitializationException {
        try (JavetScriptRuntime runtime = new JavetScriptRuntime(host)) {
            ScriptResult result = execute(runtime, "invalid", "throw new Error('broken')").join();

            assertEquals(ScriptResult.Status.FAILURE, result.status());
        }
    }

    @Test
    @SuppressWarnings("try")
    void preservesDisposedErrors() throws RuntimeInitializationException {
        try (JavetScriptRuntime runtime = new JavetScriptRuntime(host)) {
            runtime.close();

            CompletionException failure = assertThrows(CompletionException.class,
                () -> execute(runtime, "closed", "1").join());
            assertInstanceOf(RuntimeDisposedError.class, failure.getCause());
        }
    }

    @Test
    void preservesQueueFullErrors() throws RuntimeInitializationException {
        ShamooNodeRuntimeOptions options =
            new ShamooNodeRuntimeOptions(1, Duration.ofSeconds(5), Duration.ofSeconds(5));
        try (JavetScriptRuntime runtime = new JavetScriptRuntime(host, options)) {
            List<CompletableFuture<ScriptResult>> submissions = new ArrayList<>();
            for (int index = 0; index < 20; index++) {
                submissions.add(execute(runtime, "queued-" + index,
                    "new Promise(resolve => setTimeout(() => resolve('done'), 500))"));
            }
            CompletableFuture<ScriptResult> rejected = submissions.stream()
                .filter(CompletableFuture::isCompletedExceptionally)
                .findFirst()
                .orElseThrow();

            CompletionException failure = assertThrows(CompletionException.class,
                rejected::join);
            assertInstanceOf(RuntimeQueueFullError.class, failure.getCause());
        }
    }

    @Test
    void exposesOnlyMetadataCheckedPlatformCapabilities() throws RuntimeInitializationException {
        PlatformCapabilities capabilities = new PlatformCapabilities("paper", Map.of("registerEvent",
                (owner, metadata, arguments) -> owner + ":" + metadata.typeName() + ":" + arguments.getFirst()));
        try (JavetScriptRuntime runtime = new JavetScriptRuntime(
                host, new PluginId("fixture"), capabilities)) {
            ScriptResult accepted = execute(runtime, "capability", "host.registerEvent({namespace: 'paper', "
                    + "typeName: 'JoinEvent', protocolMajor: 1, protocolMinor: 0}, 'ok')").join();
            ScriptResult rejected = execute(runtime, "raw-capability", "host.registerEvent('raw')").join();

            assertEquals("fixture:JoinEvent:ok", accepted.value());
            assertEquals(ScriptResult.Status.FAILURE, rejected.status());
            ScriptResult wrongNamespace = execute(runtime, "wrong-namespace",
                    "host.registerEvent({namespace: 'velocity', typeName: 'JoinEvent', "
                            + "protocolMajor: 1, protocolMinor: 0}, 'bad')").join();
            assertEquals(ScriptResult.Status.FAILURE, wrongNamespace.status());
        }
    }

    private static CompletableFuture<ScriptResult> execute(
            JavetScriptRuntime runtime,
            String requestId,
            String source) {
        return runtime.execute(new ScriptRequest(requestId, source, Map.of())).toCompletableFuture();
    }
}
