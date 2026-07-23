package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginRuntime;
import dev.shamoo.runtime.core.PluginRuntimeContext;
import dev.shamoo.runtime.core.PluginRuntimeFactory;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;

/** Adapts the Javet manager to the engine-neutral core lifecycle factory contract. */
public final class JavetPluginRuntimeFactory implements PluginRuntimeFactory {
    private final ShamooNodeRuntimeManager manager;
    private final ShamooNodeRuntimeOptions options;
    private final Function<PluginRuntimeContext, Map<String, HostFunction>> bindings;
    private final Function<PluginRuntimeContext, RuntimeErrorReporter> reporters;
    private final BiFunction<PluginRuntimeContext, ShamooNodeRuntime, PluginRuntime> lifecycle;

    public JavetPluginRuntimeFactory(
            ShamooNodeRuntimeManager manager,
            ShamooNodeRuntimeOptions options,
            Function<PluginRuntimeContext, Map<String, HostFunction>> bindings,
            Function<PluginRuntimeContext, RuntimeErrorReporter> reporters,
            BiFunction<PluginRuntimeContext, ShamooNodeRuntime, PluginRuntime> lifecycle) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.options = Objects.requireNonNull(options, "options");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.reporters = Objects.requireNonNull(reporters, "reporters");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<PluginRuntime> create(PluginRuntimeContext context) {
        Objects.requireNonNull(context, "context");
        try {
            ShamooNodeRuntime runtime = manager.create(
                    context.candidate().pluginId(),
                    context.candidate().root(),
                    context.candidate().descriptor().node(),
                    Map.copyOf(bindings.apply(context)),
                    options,
                    reporters.apply(context));
            PluginRuntime hooks = Objects.requireNonNull(lifecycle.apply(context, runtime), "lifecycle result");
            return CompletableFuture.completedFuture(new ManagedRuntime(
                    manager, context.candidate().pluginId(), hooks));
        } catch (RuntimeException exception) {
            try {
                manager.close(context.candidate().pluginId());
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            return CompletableFuture.failedFuture(exception);
        }
    }

    private record ManagedRuntime(
            ShamooNodeRuntimeManager manager,
            dev.shamoo.runtime.core.PluginId pluginId,
            PluginRuntime delegate)
            implements PluginRuntime {
        @Override
        public CompletionStage<Void> load() {
            return delegate.load();
        }

        @Override
        public CompletionStage<Void> enable() {
            return delegate.enable();
        }

        @Override
        public CompletionStage<Void> ready() {
            return delegate.ready();
        }

        @Override
        public CompletionStage<Void> drain() {
            return delegate.drain();
        }

        @Override
        public CompletionStage<Void> disable() {
            return delegate.disable();
        }

        @Override
        public CompletionStage<Void> unload() {
            CompletionStage<Void> stage;
            try {
                stage = Objects.requireNonNull(delegate.unload(), "unload hook result");
            } catch (RuntimeException exception) {
                manager.close(pluginId);
                throw exception;
            }
            return stage.whenComplete((ignored, failure) -> manager.close(pluginId));
        }
    }
}
