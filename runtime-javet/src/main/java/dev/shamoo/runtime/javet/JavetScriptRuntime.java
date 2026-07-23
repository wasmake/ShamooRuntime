package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.RuntimeHost;
import dev.shamoo.runtime.core.RuntimeInitializationException;
import dev.shamoo.runtime.core.ScriptRuntime;
import dev.shamoo.runtime.protocol.FilesystemPolicy;
import dev.shamoo.runtime.protocol.NodePolicy;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import dev.shamoo.runtime.protocol.ScriptRequest;
import dev.shamoo.runtime.protocol.ScriptResult;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;

/** Protocol adapter retained for the platform bootstraps, backed by a confined per-plugin Node runtime. */
public final class JavetScriptRuntime implements ScriptRuntime {
    private final RuntimeHost host;
    private final ShamooNodeRuntime delegate;

    public JavetScriptRuntime(RuntimeHost host) throws RuntimeInitializationException {
        this(host, ShamooNodeRuntimeOptions.DEFAULT);
    }

    JavetScriptRuntime(RuntimeHost host, ShamooNodeRuntimeOptions options) throws RuntimeInitializationException {
        this.host = Objects.requireNonNull(host, "host");
        NodePolicy policy = new NodePolicy(
            List.of(), new FilesystemPolicy(List.of(), List.of()), false, false, false, false);
        try {
            delegate = ShamooNodeRuntime.create(
                new PluginId(
                    "bootstrap-" + host.platformName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-")),
                Path.of("."),
                policy,
                Map.of(),
                options,
                error -> host.logger().log(System.Logger.Level.ERROR, error.getMessage(), error));
        } catch (RuntimeCreationError | IllegalArgumentException exception) {
            throw new RuntimeInitializationException("unable to initialize Node runtime", exception);
        }
    }

    @Override
    public ProtocolVersion protocolVersion() {
        return ProtocolVersion.CURRENT;
    }

    @Override
    public CompletionStage<ScriptResult> execute(ScriptRequest request) {
        Objects.requireNonNull(request, "request");
        Instant started = Instant.now();
        return delegate.evaluate(request.source(), request.requestId()).handle((value, failure) -> {
            Duration duration = Duration.between(started, Instant.now());
            if (failure == null) {
                return ScriptResult.success(request.requestId(), String.valueOf(value), duration);
            }
            if (!(failure instanceof RuntimeEvaluationError)) {
                if (failure instanceof RuntimeException runtimeFailure) {
                    throw runtimeFailure;
                }
                throw new CompletionException(failure);
            }
            host.logger().log(System.Logger.Level.WARNING, "Script execution failed: " + request.requestId(), failure);
            return ScriptResult.failure(request.requestId(), failure.getMessage(), duration);
        });
    }

    @Override
    public boolean isClosed() {
        return delegate.state() != dev.shamoo.runtime.core.RuntimeState.RUNNING;
    }

    @Override
    public void close() {
        delegate.close();
    }
}
