package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.CompiledBindingMetadata;
import dev.shamoo.runtime.core.CloseNotifyingResource;
import dev.shamoo.runtime.core.PluginRuntime;
import dev.shamoo.runtime.core.HotStatePluginRuntime;
import dev.shamoo.runtime.core.PluginRuntimeContext;
import dev.shamoo.runtime.core.PluginRuntimeFactory;
import dev.shamoo.runtime.core.PlatformOperationResult;
import dev.shamoo.runtime.core.InvocationAdmission;
import dev.shamoo.runtime.core.InvocationController;
import dev.shamoo.runtime.core.InvocationRejectedError;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceCategory;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.util.ArrayList;
import java.util.Map;
import java.util.List;
import java.util.LinkedHashMap;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicBoolean;
import dev.shamoo.runtime.core.DependentReloadPolicy;
import dev.shamoo.runtime.core.PluginServiceProxy;
import dev.shamoo.runtime.core.ScriptCallback;
import dev.shamoo.runtime.protocol.EventContract;
import dev.shamoo.runtime.protocol.PlatformKind;
import dev.shamoo.runtime.protocol.SemanticVersion;
import dev.shamoo.runtime.protocol.SemverRange;
import dev.shamoo.runtime.protocol.ServiceContract;
import dev.shamoo.runtime.protocol.VersionParser;

/** Adapts the Javet manager to the engine-neutral core lifecycle factory contract. */
@SuppressWarnings({"PMD.CloseResource", "PMD.CompareObjectsWithEquals"})
public final class JavetPluginRuntimeFactory implements PluginRuntimeFactory {
    private static final int MAX_CALLBACK_ADAPTATION_DEPTH = 32;
    private static final int MAX_RESULT_NORMALIZATION_DEPTH = 32;
    private static final Set<String> ASYNC_COMMAND_CONTEXT_OPERATIONS = Set.of(
            "paperCommandReply",
            "paperCommandOpenInventory",
            "paperCommandGiveItem",
            "paperCommandFindPlayer",
            "paperCommandMainHand",
            "paperCommandTakeMainHand");
    private final ShamooNodeRuntimeManager manager;
    private final ShamooNodeRuntimeOptions options;
    private final Function<PluginRuntimeContext, Map<String, HostFunction>> bindings;
    private final Function<PluginRuntimeContext, RuntimeErrorReporter> reporters;
    private final BiFunction<PluginRuntimeContext, ShamooNodeRuntime, PluginRuntime> lifecycle;
    private final PlatformKind platform;

    public JavetPluginRuntimeFactory(
            ShamooNodeRuntimeManager manager,
            ShamooNodeRuntimeOptions options,
            Function<PluginRuntimeContext, Map<String, HostFunction>> bindings,
            Function<PluginRuntimeContext, RuntimeErrorReporter> reporters,
            BiFunction<PluginRuntimeContext, ShamooNodeRuntime, PluginRuntime> lifecycle,
            PlatformKind platform) {
        this.manager = Objects.requireNonNull(manager, "manager");
        this.options = Objects.requireNonNull(options, "options");
        this.bindings = Objects.requireNonNull(bindings, "bindings");
        this.reporters = Objects.requireNonNull(reporters, "reporters");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.platform = Objects.requireNonNull(platform, "platform");
    }

    @Override
    @SuppressWarnings("PMD.CloseResource")
    public CompletionStage<PluginRuntime> create(PluginRuntimeContext context) {
        Objects.requireNonNull(context, "context");
        try {
            ShamooPluginMetadata metadata = ShamooPluginMetadata.from(context.candidate().descriptor(), platform);
            AtomicReference<ShamooNodeRuntime> runtimeReference = new AtomicReference<>();
            Map<String, HostFunction> hostBindings = new LinkedHashMap<>(bindings.apply(context));
            hostBindings.replaceAll((name, binding) -> managedHostBinding(
                    binding, context.resources(), context.candidate().pluginId(), name, context.invocations(),
                    runtimeReference));
            Map<String, PluginServiceProxy> serviceProxies = new java.util.concurrent.ConcurrentHashMap<>();
            addCoreBindings(context, metadata, hostBindings, runtimeReference, serviceProxies);
            context.platformCapabilities().operations().forEach((name, operation) -> {
                HostFunction previous = hostBindings.putIfAbsent(name, arguments -> {
                    if (arguments.isEmpty() || !(arguments.getFirst() instanceof Map<?, ?> bindingMetadata)) {
                        throw new IllegalArgumentException(name + " requires compiled binding metadata");
                    }
                    CompiledBindingMetadata parsedBinding = CompiledBindingMetadata.from(bindingMetadata);
                    if (!metadata.permitsPlatformOperation(name, parsedBinding)) {
                        throw new SecurityException("compiled metadata does not authorize platform operation " + name);
                    }
                    InvocationAdmission.Lease lease = requiresOperationAdmission(name)
                            ? context.invocations().admit() : null;
                    AdaptedArguments adapted = null;
                    try {
                        adapted = adaptCallbacks(arguments.subList(1, arguments.size()),
                                runtimeReference, context.invocations());
                        AdaptedArguments invocationArguments = adapted;
                        Object result = dev.shamoo.runtime.core.PlatformInvocationScope.invoke(
                                context.generationId(), () -> context.platformCapabilities().invoke(
                                        name, context.candidate().pluginId(), parsedBinding,
                                        invocationArguments.values()));
                        Object normalized = normalizePlatformResult(result, context.resources(),
                                context.candidate().pluginId(), name, adapted.ownership(), 0);
                        return keepAdmissionUntilSettled(normalized, lease);
                    } catch (Exception | Error failure) {
                        if (adapted != null) {
                            adapted.ownership().close();
                        }
                        if (lease != null) {
                            lease.close();
                        }
                        throw failure;
                    }
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

    private static AdaptedArguments adaptCallbacks(List<Object> arguments,
            AtomicReference<ShamooNodeRuntime> runtime, InvocationController invocations) {
        CallbackOwnership ownership = new CallbackOwnership();
        try {
            Function<String, ScriptCallback> callbackAdapter = callback -> {
                ScriptCallback result = admittedCallback(invocations, new ScriptCallback() {
                    private final AtomicBoolean closed = new AtomicBoolean();

                    @Override
                    public CompletionStage<Object> invoke(List<Object> values) {
                        return requireRuntime(runtime).invokeCallback(callback, values);
                    }

                    @Override
                    public void close() {
                        if (closed.compareAndSet(false, true)) {
                            ShamooNodeRuntime current = runtime.get();
                            if (current != null) {
                                current.unregisterCallback(callback);
                            }
                        }
                    }
                });
                ownership.add(result);
                return result;
            };
            return new AdaptedArguments(adaptCallbackPayloads(arguments, callbackAdapter), ownership);
        } catch (RuntimeException | Error failure) {
            ownership.close();
            throw failure;
        }
    }

    static boolean requiresOperationAdmission(String name) {
        return ASYNC_COMMAND_CONTEXT_OPERATIONS.contains(name);
    }

    static List<Object> adaptCallbackPayloads(List<Object> arguments,
            Function<String, ScriptCallback> callbacks) {
        Objects.requireNonNull(arguments, "arguments");
        Objects.requireNonNull(callbacks, "callbacks");
        List<Object> result = new ArrayList<>(arguments.size());
        for (Object argument : arguments) {
            result.add(adaptCallbackMarkers(argument, callbacks, 0));
        }
        return java.util.Collections.unmodifiableList(result);
    }

    static Object keepAdmissionUntilSettled(Object result, InvocationAdmission.Lease lease) {
        if (lease == null) {
            return result;
        }
        if (result instanceof CompletionStage<?> stage) {
            return stage.whenComplete((ignored, failure) -> lease.close());
        }
        lease.close();
        return result;
    }

    static ScriptCallback admittedCallback(InvocationController invocations, ScriptCallback callback) {
        Objects.requireNonNull(invocations, "invocations");
        Objects.requireNonNull(callback, "callback");
        return new AdmittedScriptCallback(invocations, callback);
    }

    private static final class AdmittedScriptCallback implements ScriptCallback, CloseNotifyingResource {
        private final AtomicBoolean closed = new AtomicBoolean();
        private final AtomicReference<Runnable> closeNotification = new AtomicReference<>();
        private final InvocationController invocations;
        private final ScriptCallback callback;

        private AdmittedScriptCallback(InvocationController invocations, ScriptCallback callback) {
            this.invocations = invocations;
            this.callback = callback;
        }

        @Override
        public CompletionStage<Object> invoke(List<Object> arguments) {
            InvocationAdmission.Lease lease = invocations.admit();
            try {
                CompletionStage<Object> stage = Objects.requireNonNull(
                        callback.invoke(arguments), "script callback completion");
                stage.whenComplete((ignored, failure) -> lease.close());
                return stage;
            } catch (RuntimeException | Error failure) {
                lease.close();
                throw failure;
            }
        }

        @Override
        public void onClosed(Runnable notification) {
            Objects.requireNonNull(notification, "notification");
            if (!closeNotification.compareAndSet(null, notification)) {
                throw new IllegalStateException("callback close notification is already registered");
            }
            if (closed.get()) {
                notifyClosed();
            }
        }

        @Override
        public void close() {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            try {
                callback.close();
            } finally {
                notifyClosed();
            }
        }

        private void notifyClosed() {
            Runnable notification = closeNotification.getAndSet(null);
            if (notification != null) {
                notification.run();
            }
        }
    }

    static Object normalizePlatformResult(Object result, ResourceRegistry resources, PluginId owner, String name) {
        return normalizePlatformResult(result, resources, owner, name, new CallbackOwnership(), 0);
    }

    static HostFunction managedHostBinding(HostFunction binding, ResourceRegistry resources, PluginId owner,
            String name, InvocationController invocations) {
        return managedHostBinding(binding, resources, owner, name, invocations, null);
    }

    private static HostFunction managedHostBinding(HostFunction binding, ResourceRegistry resources, PluginId owner,
            String name, InvocationController invocations, AtomicReference<ShamooNodeRuntime> runtime) {
        Objects.requireNonNull(binding, "binding");
        Objects.requireNonNull(invocations, "invocations");
        return arguments -> {
            InvocationAdmission.Lease lease = null;
            AdaptedArguments adapted = null;
            if (invocations.snapshot().accepting()) {
                try {
                    lease = invocations.admit();
                } catch (InvocationRejectedError failure) {
                    return CompletableFuture.failedFuture(failure);
                }
            }
            try {
                adapted = runtime == null
                        ? new AdaptedArguments(arguments, new CallbackOwnership())
                        : adaptCallbacks(arguments, runtime, invocations);
                Object normalized = normalizePlatformResult(
                        binding.invoke(adapted.values(), lease != null), resources, owner, name,
                        adapted.ownership(), 0);
                return keepAdmissionUntilSettled(normalized, lease);
            } catch (Exception | Error failure) {
                if (adapted != null) {
                    adapted.ownership().close();
                }
                if (lease != null) {
                    lease.close();
                }
                throw failure;
            }
        };
    }

    static Object normalizePlatformResult(Object result, ResourceRegistry resources, PluginId owner, String name,
            List<ScriptCallback> callbacks) {
        CallbackOwnership ownership = new CallbackOwnership();
        callbacks.forEach(ownership::add);
        return normalizePlatformResult(result, resources, owner, name, ownership, 0);
    }

    private static Object normalizePlatformResult(Object result, ResourceRegistry resources, PluginId owner,
            String name, CallbackOwnership callbacks, int depth) {
        if (depth > MAX_RESULT_NORMALIZATION_DEPTH) {
            callbacks.close();
            throw new IllegalArgumentException("platform operation result nesting exceeds "
                    + MAX_RESULT_NORMALIZATION_DEPTH);
        }
        if (result instanceof CompletionStage<?> stage) {
            return stage.handle((value, failure) -> {
                if (failure != null) {
                    callbacks.close();
                    return CompletableFuture.<Object>failedFuture(unwrap(failure));
                }
                try {
                    Object normalized = normalizePlatformResult(value, resources, owner, name, callbacks, depth + 1);
                    return normalized instanceof CompletionStage<?> nested
                            ? nested.thenApply(item -> (Object) item)
                            : CompletableFuture.completedFuture(normalized);
                } catch (RuntimeException | Error normalizationFailure) {
                    callbacks.close();
                    return CompletableFuture.<Object>failedFuture(normalizationFailure);
                }
            }).thenCompose(Function.identity());
        }
        if (result instanceof PlatformOperationResult<?> operationResult) {
            AutoCloseable resource = operationResult.resource();
            if (resource != null) {
                try {
                    own(resources, owner, name, resource);
                    callbacks.release();
                } catch (RuntimeException | Error failure) {
                    callbacks.close();
                    throw failure;
                }
                return normalizePlatformResult(operationResult.value(), resources, owner, name,
                        new CallbackOwnership(), depth + 1);
            }
            return normalizePlatformResult(operationResult.value(), resources, owner, name, callbacks, depth + 1);
        }
        if (result instanceof AutoCloseable resource) {
            own(resources, owner, name, callbacks.attach(resource));
            return true;
        }
        if (successful(result) && callbacks.hasCallbacks()) {
            own(resources, owner, name, callbacks);
        } else {
            callbacks.close();
        }
        return result;
    }

    private static boolean successful(Object result) {
        return result != null && !Boolean.FALSE.equals(result);
    }

    private static void own(ResourceRegistry resources, PluginId owner, String name, AutoCloseable resource) {
        resources.register(owner, ResourceCategory.GENERIC, "platform operation " + name, resource);
    }

    private static Throwable unwrap(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }

    static Object adaptCallbackMarkers(Object value, Function<String, ScriptCallback> callbacks) {
        return adaptCallbackMarkers(value, Objects.requireNonNull(callbacks, "callbacks"), 0);
    }

    private static Object adaptCallbackMarkers(
            Object value, Function<String, ScriptCallback> callbacks, int depth) {
        if (depth > MAX_CALLBACK_ADAPTATION_DEPTH) {
            throw new IllegalArgumentException("platform argument nesting exceeds "
                    + MAX_CALLBACK_ADAPTATION_DEPTH);
        }
        if (value == null || value instanceof String || value instanceof Number || value instanceof Boolean) {
            return value;
        }
        if (value instanceof byte[] bytes) {
            return bytes.clone();
        }
        if (value instanceof List<?> values) {
            List<Object> adapted = new java.util.ArrayList<>(values.size());
            values.forEach(item -> adapted.add(adaptCallbackMarkers(item, callbacks, depth + 1)));
            return java.util.Collections.unmodifiableList(adapted);
        }
        if (value instanceof Map<?, ?> values) {
            if (values.size() == 1 && values.get("$callback") instanceof String callback) {
                return Objects.requireNonNull(callbacks.apply(callback), "adapted callback");
            }
            Map<String, Object> adapted = new LinkedHashMap<>();
            values.forEach((key, item) -> {
                if (!(key instanceof String name)) {
                    throw new IllegalArgumentException("platform argument object keys must be strings");
                }
                adapted.put(name, adaptCallbackMarkers(item, callbacks, depth + 1));
            });
            return java.util.Collections.unmodifiableMap(adapted);
        }
        throw new IllegalArgumentException("platform arguments must contain only plain data and callback markers");
    }

    private record AdaptedArguments(List<Object> values, CallbackOwnership ownership) {
    }

    private static final class CallbackOwnership implements CloseNotifyingResource {
        private final List<ScriptCallback> callbacks = new ArrayList<>();
        private final AtomicBoolean claimed = new AtomicBoolean();
        private final AtomicReference<Runnable> closeNotification = new AtomicReference<>();

        private synchronized void add(ScriptCallback callback) {
            if (claimed.get()) {
                callback.close();
            } else {
                callbacks.add(callback);
                if (callback instanceof CloseNotifyingResource notifying) {
                    notifying.onClosed(() -> callbackClosed(callback));
                }
            }
        }

        private void callbackClosed(ScriptCallback callback) {
            Runnable notification = null;
            synchronized (this) {
                callbacks.removeIf(current -> current == callback);
                if (callbacks.isEmpty() && claimed.compareAndSet(false, true)) {
                    notification = closeNotification.getAndSet(null);
                }
            }
            if (notification != null) {
                notification.run();
            }
        }

        private AutoCloseable attach(AutoCloseable resource) {
            if (!claimed.compareAndSet(false, true)) {
                return resource;
            }
            List<ScriptCallback> owned;
            synchronized (this) {
                owned = List.copyOf(callbacks);
                callbacks.clear();
            }
            return () -> {
                Exception failure = null;
                try {
                    resource.close();
                } catch (Exception exception) {
                    failure = exception;
                }
                for (ScriptCallback callback : owned) {
                    try {
                        callback.close();
                    } catch (RuntimeException exception) {
                        if (failure == null) {
                            failure = exception;
                        } else {
                            failure.addSuppressed(exception);
                        }
                    }
                }
                if (failure != null) {
                    throw failure;
                }
            };
        }

        private synchronized boolean hasCallbacks() {
            return !callbacks.isEmpty();
        }

        private void release() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            synchronized (this) {
                callbacks.clear();
            }
        }

        @Override
        public synchronized void onClosed(Runnable notification) {
            Objects.requireNonNull(notification, "notification");
            if (!closeNotification.compareAndSet(null, notification)) {
                throw new IllegalStateException("callback ownership close notification is already registered");
            }
            if (claimed.get() && callbacks.isEmpty()) {
                notifyClosed();
            }
        }

        @Override
        public void close() {
            if (!claimed.compareAndSet(false, true)) {
                return;
            }
            List<ScriptCallback> closing;
            synchronized (this) {
                closing = List.copyOf(callbacks);
                callbacks.clear();
            }
            try {
                closing.forEach(ScriptCallback::close);
            } finally {
                notifyClosed();
            }
        }

        private void notifyClosed() {
            Runnable notification = closeNotification.getAndSet(null);
            if (notification != null) {
                notification.run();
            }
        }
    }

    private static void addCoreBindings(
            PluginRuntimeContext context,
            ShamooPluginMetadata metadata,
            Map<String, HostFunction> bindings,
            AtomicReference<ShamooNodeRuntime> runtime,
            Map<String, PluginServiceProxy> proxies) {
        putBinding(bindings, "shamooProvideService", arguments -> {
            String name = string(arguments, 0);
            SemanticVersion version = VersionParser.parseSemantic(string(arguments, 1));
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
                    new EventContract(string(arguments, 0), VersionParser.parseSemantic(string(arguments, 1))),
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
