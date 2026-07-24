package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.PluginRuntime;
import dev.shamoo.runtime.core.HotStatePluginRuntime;
import dev.shamoo.runtime.core.PluginRuntimeContext;
import dev.shamoo.runtime.core.PluginRuntimeFactory;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;
import dev.shamoo.runtime.core.DependentReloadPolicy;
import dev.shamoo.runtime.core.PluginServiceProxy;
import dev.shamoo.runtime.core.ScriptCallback;
import dev.shamoo.runtime.protocol.EventContract;
import dev.shamoo.runtime.protocol.SemanticVersion;
import dev.shamoo.runtime.protocol.SemverRange;
import dev.shamoo.runtime.protocol.ServiceContract;

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
            ShamooPluginMetadata metadata = ShamooPluginMetadata.load(context.candidate().root(),
                    context.candidate().descriptor(), metadataPlatform(context));
            Map<String, HostFunction> hostBindings = new LinkedHashMap<>(bindings.apply(context));
            AtomicReference<ShamooNodeRuntime> runtimeReference = new AtomicReference<>();
            Map<String, PluginServiceProxy> serviceProxies = new java.util.concurrent.ConcurrentHashMap<>();
            addCoreBindings(context, metadata, hostBindings, runtimeReference, serviceProxies);
            context.platformCapabilities().operations().forEach((name, operation) -> {
                HostFunction previous = hostBindings.putIfAbsent(name, arguments -> {
                    if (!metadata.permitsPlatformOperation(name)) {
                        throw new SecurityException("compiled metadata does not authorize platform operation " + name);
                    }
                    if (arguments.isEmpty() || !(arguments.getFirst() instanceof Map<?, ?> bindingMetadata)) {
                        throw new IllegalArgumentException(name + " requires compiled binding metadata");
                    }
                    Object result = context.platformCapabilities().invoke(name, context.candidate().pluginId(),
                            bindingMetadata, adaptCallbacks(arguments.subList(1, arguments.size()), runtimeReference));
                    if (result instanceof AutoCloseable resource) {
                        context.resources().register(context.candidate().pluginId(),
                                dev.shamoo.runtime.core.ResourceCategory.GENERIC,
                                "platform operation " + name, resource);
                        return true;
                    }
                    return result;
                });
                if (previous != null) {
                    throw new IllegalArgumentException("duplicate platform host binding: " + name);
                }
            });
            ShamooNodeRuntime runtime = manager.create(
                    context.candidate().pluginId(),
                    context.generationId(),
                    context.candidate().root(),
                    context.candidate().descriptor().node(),
                    Map.copyOf(hostBindings),
                    options,
                    reporters.apply(context));
            runtimeReference.set(runtime);
            PluginRuntime hooks = Objects.requireNonNull(lifecycle.apply(context, runtime), "lifecycle result");
            PluginRuntime managed = hooks instanceof HotStatePluginRuntime hot
                    ? new ManagedHotStateRuntime(manager, context.candidate().pluginId(),
                            context.generationId(), hot)
                    : new ManagedRuntime(manager, context.candidate().pluginId(), context.generationId(), hooks);
            return CompletableFuture.completedFuture(managed);
        } catch (RuntimeException exception) {
            try {
                manager.close(context.candidate().pluginId(), context.generationId());
            } catch (RuntimeException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static List<Object> adaptCallbacks(
            List<Object> arguments, AtomicReference<ShamooNodeRuntime> runtime) {
        return arguments.stream().map(value -> {
            if (value instanceof Map<?, ?> marker && marker.size() == 1
                    && marker.get("$callback") instanceof String callback) {
                return (Object) (ScriptCallback) values -> requireRuntime(runtime).invokeCallback(callback, values);
            }
            return value;
        }).toList();
    }

    private static void addCoreBindings(
            PluginRuntimeContext context,
            ShamooPluginMetadata metadata,
            Map<String, HostFunction> bindings,
            AtomicReference<ShamooNodeRuntime> runtime,
            Map<String, PluginServiceProxy> proxies) {
        putBinding(bindings, "shamooProvideService", arguments -> {
            String name = string(arguments, 0);
            SemanticVersion version = new SemanticVersion(string(arguments, 1));
            if (!version.value().equals(metadata.services().get(name))) {
                throw new SecurityException("compiled metadata does not authorize service provider " + name);
            }
            String callback = string(arguments, 2);
            context.services().provide(new ServiceContract(name, version),
                    (operation, values) -> requireRuntime(runtime).invokeCallback(
                            callback, List.of(operation, values)));
            return true;
        });
        putBinding(bindings, "shamooAcquireService", arguments -> {
            String name = string(arguments, 0);
            String range = string(arguments, 1);
            DependentReloadPolicy policy = dependentPolicy(string(arguments, 2));
            String metadataPolicy = policy == DependentReloadPolicy.KEEP_RUNNING ? "keep-running" : "reload";
            if (!range.equals(metadata.consumers().get(name))
                    || !metadataPolicy.equals(metadata.consumerPolicies().get(name))) {
                throw new SecurityException("compiled metadata does not authorize service consumer " + name);
            }
            String handle = java.util.UUID.randomUUID().toString();
            PluginServiceProxy proxy = context.services().acquire(name,
                    new SemverRange(range), policy);
            proxies.put(handle, proxy);
            return handle;
        });
        putBinding(bindings, "shamooInvokeService", arguments -> {
            PluginServiceProxy proxy = proxies.get(string(arguments, 0));
            if (proxy == null) {
                return CompletableFuture.failedFuture(new IllegalArgumentException("unknown service handle"));
            }
            return proxy.invoke(string(arguments, 1), list(arguments, 2));
        });
        putBinding(bindings, "shamooSubscribeEvent", arguments -> {
            authorizeEvent(metadata, arguments);
            String callback = string(arguments, 2);
            context.events().subscribe(string(arguments, 0), new SemverRange(string(arguments, 1)),
                    payload -> requireRuntime(runtime).invokeCallback(callback,
                            java.util.Collections.singletonList(payload))
                            .thenApply(ignored -> null));
            return true;
        });
        putBinding(bindings, "shamooPublishEvent", arguments -> {
            authorizeEvent(metadata, arguments);
            return context.events().publish(
                    new EventContract(string(arguments, 0), new SemanticVersion(string(arguments, 1))),
                    arguments.get(2));
        });
        putBinding(bindings, "shamooMetadata", arguments -> {
            if (!arguments.isEmpty()) {
                throw new IllegalArgumentException("shamooMetadata does not accept arguments");
            }
            return metadata.data();
        });
    }

    private static void authorizeEvent(ShamooPluginMetadata metadata, List<Object> arguments) {
        String name = string(arguments, 0);
        String version = string(arguments, 1);
        if (!version.equals(metadata.events().get(name))) {
            throw new SecurityException("compiled metadata does not authorize event " + name);
        }
    }

    private static dev.shamoo.runtime.protocol.PlatformKind metadataPlatform(PluginRuntimeContext context) {
        boolean paper = context.candidate().descriptor().platforms().paper().enabled();
        boolean velocity = context.candidate().descriptor().platforms().velocity().enabled();
        if (paper == velocity) {
            String namespace = context.platformCapabilities().bindingNamespace();
            return "paper".equals(namespace) ? dev.shamoo.runtime.protocol.PlatformKind.PAPER
                    : dev.shamoo.runtime.protocol.PlatformKind.VELOCITY;
        }
        return paper ? dev.shamoo.runtime.protocol.PlatformKind.PAPER
                : dev.shamoo.runtime.protocol.PlatformKind.VELOCITY;
    }

    private static ShamooNodeRuntime requireRuntime(AtomicReference<ShamooNodeRuntime> runtime) {
        return Objects.requireNonNull(runtime.get(), "plugin runtime is not initialized");
    }

    private static DependentReloadPolicy dependentPolicy(String value) {
        return switch (value) {
            case "KEEP_RUNNING", "keep-running" -> DependentReloadPolicy.KEEP_RUNNING;
            case "RELOAD", "reload" -> DependentReloadPolicy.RELOAD;
            default -> throw new IllegalArgumentException("unknown dependent reload policy: " + value);
        };
    }

    private static void putBinding(Map<String, HostFunction> bindings, String name, HostFunction function) {
        if (bindings.putIfAbsent(name, function) != null) {
            throw new IllegalArgumentException("reserved host binding: " + name);
        }
    }

    private static String string(List<Object> arguments, int index) {
        if (index >= arguments.size() || !(arguments.get(index) instanceof String value)) {
            throw new IllegalArgumentException("host argument " + index + " must be a string");
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private static List<Object> list(List<Object> arguments, int index) {
        if (index >= arguments.size() || !(arguments.get(index) instanceof List<?> values)) {
            throw new IllegalArgumentException("host argument " + index + " must be an array");
        }
        return (List<Object>) values;
    }

    private record ManagedRuntime(
            ShamooNodeRuntimeManager manager,
            dev.shamoo.runtime.core.PluginId pluginId,
            java.util.UUID generationId,
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
                manager.close(pluginId, generationId);
                throw exception;
            }
            return closeAfter(stage, manager, pluginId, generationId);
        }
    }

    private record ManagedHotStateRuntime(
            ShamooNodeRuntimeManager manager,
            dev.shamoo.runtime.core.PluginId pluginId,
            java.util.UUID generationId,
            HotStatePluginRuntime delegate)
            implements HotStatePluginRuntime {
        @Override public CompletionStage<Void> load() { return delegate.load(); }
        @Override public CompletionStage<Void> enable() { return delegate.enable(); }
        @Override public CompletionStage<Void> ready() { return delegate.ready(); }
        @Override public CompletionStage<Void> drain() { return delegate.drain(); }
        @Override public CompletionStage<Void> disable() { return delegate.disable(); }
        @Override public CompletionStage<byte[]> exportHotState() { return delegate.exportHotState(); }
        @Override public CompletionStage<Void> importHotState(byte[] state) {
            return delegate.importHotState(Objects.requireNonNull(state, "state").clone());
        }
        @Override
        public CompletionStage<Void> unload() {
            CompletionStage<Void> stage;
            try {
                stage = Objects.requireNonNull(delegate.unload(), "unload hook result");
            } catch (RuntimeException exception) {
                manager.close(pluginId, generationId);
                throw exception;
            }
            return closeAfter(stage, manager, pluginId, generationId);
        }
    }

    private static CompletionStage<Void> closeAfter(
            CompletionStage<Void> stage,
            ShamooNodeRuntimeManager manager,
            dev.shamoo.runtime.core.PluginId pluginId,
            java.util.UUID generationId) {
        return stage.handle((ignored, failure) -> failure).thenCompose(failure ->
                CompletableFuture.runAsync(() -> manager.close(pluginId, generationId))
                        .handle((ignored, closeFailure) -> {
                    Throwable cause = failure;
                    if (cause != null && closeFailure != null) {
                        cause.addSuppressed(closeFailure);
                    } else if (cause == null) {
                        cause = closeFailure;
                    }
                    if (cause != null) {
                        throw new java.util.concurrent.CompletionException(cause);
                    }
                    return null;
                        }));
    }
}
