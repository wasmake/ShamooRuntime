package dev.shamoo.runtime.javet;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import dev.shamoo.runtime.core.RuntimeHost;
import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.ScriptRequest;
import dev.shamoo.runtime.protocol.ScriptResult;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** A single-isolate V8 runtime. All isolate access is serialized on one virtual-thread executor. */
public final class JavetScriptRuntime implements ScriptRuntime {
    private final RuntimeHost host;
    private final V8Runtime v8Runtime;
    private final ExecutorService executor;
    private final AtomicBoolean closed = new AtomicBoolean();

    public JavetScriptRuntime(RuntimeHost host) throws RuntimeInitializationException {
        this.host = Objects.requireNonNull(host, "host");
        this.executor = Executors.newSingleThreadExecutor(Thread.ofVirtual().name("shamoo-v8-", 0).factory());
        try {
            this.v8Runtime = V8Host.getV8Instance().createV8Runtime();
        } catch (JavetException exception) {
            executor.shutdownNow();
            throw new RuntimeInitializationException("unable to initialize V8", exception);
        }
    }

    @Override
    public ProtocolVersion protocolVersion() {
        return ProtocolVersion.CURRENT;
    }

    @Override
    public CompletionStage<ScriptResult> execute(ScriptRequest request) {
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("runtime is closed"));
        }
        return CompletableFuture.supplyAsync(() -> evaluate(request), executor);
    }

    private ScriptResult evaluate(ScriptRequest request) {
        Instant started = Instant.now();
        if (closed.get()) {
            throw new IllegalStateException("runtime is closed");
        }
        try {
            String value = v8Runtime.getExecutor(request.source())
                .setResourceName(request.requestId())
                .executeString();
            return ScriptResult.success(request.requestId(), value, Duration.between(started, Instant.now()));
        } catch (JavetException exception) {
            host.logger().log(
                System.Logger.Level.WARNING, "Script execution failed: " + request.requestId(), exception);
            return ScriptResult.failure(
                request.requestId(), exception.getMessage(), Duration.between(started, Instant.now()));
        }
    }

    @Override
    public boolean isClosed() {
        return closed.get();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdown();
            try {
                if (!executor.awaitTermination(30, TimeUnit.SECONDS)) {
                    executor.shutdownNow();
                }
                v8Runtime.close();
            } catch (InterruptedException exception) {
                executor.shutdownNow();
                Thread.currentThread().interrupt();
                throw new IllegalStateException("interrupted while closing runtime", exception);
            } catch (JavetException exception) {
                throw new IllegalStateException("unable to close V8 runtime", exception);
            }
        }
    }
}
