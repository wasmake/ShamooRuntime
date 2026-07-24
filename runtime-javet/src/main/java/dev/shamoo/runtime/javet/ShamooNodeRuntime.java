package dev.shamoo.runtime.javet;

import com.caoccao.javet.enums.JavetPromiseRejectEvent;
import com.caoccao.javet.enums.V8AwaitMode;
import com.caoccao.javet.exceptions.BaseJavetScriptingException;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.exceptions.JavetScriptingError;
import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.V8Guard;
import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.callback.IJavetDirectCallable;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.JavetCallbackType;
import com.caoccao.javet.interop.executors.IV8Executor;
import com.caoccao.javet.interop.options.NodeRuntimeOptions;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.IV8Module;
import com.caoccao.javet.values.reference.V8Module;
import com.caoccao.javet.values.reference.V8ValueError;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import com.caoccao.javet.values.reference.V8ValuePromise;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.core.RuntimeMetricsSnapshot;
import dev.shamoo.runtime.core.RuntimePermissions;
import dev.shamoo.runtime.core.RuntimeState;
import dev.shamoo.runtime.core.SourceMapRegistry;
import dev.shamoo.runtime.core.SourcePosition;
import dev.shamoo.runtime.protocol.NodePolicy;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One policy-controlled Javet Node isolate owned by exactly one platform thread. */
@SuppressWarnings({
    "PMD.AvoidCatchingThrowable",
    "PMD.AvoidDuplicateLiterals",
    "PMD.AvoidFieldNameMatchingMethodName",
    "PMD.AvoidLiteralsInIfCondition",
    "PMD.CloseResource",
    "PMD.CompareObjectsWithEquals",
    "PMD.NullAssignment",
    "PMD.PreserveStackTrace",
    "PMD.UnusedLocalVariable"
})
public final class ShamooNodeRuntime implements AutoCloseable {
    private static final String PLUGIN_PREFIX = "plugin:/";
    private static final Set<String> SAFE_BUILTINS = Set.of(
        "node:assert", "node:buffer", "node:path", "node:querystring", "node:string_decoder", "node:url", "node:util");
    private static final Set<String> ALWAYS_DENIED_BUILTINS = Set.of(
        "node:child_process", "node:cluster", "node:dgram", "node:dns", "node:fs", "node:http", "node:http2",
        "node:https", "node:module", "node:net", "node:process", "node:tls", "node:vm", "node:worker_threads");

    private final PluginId pluginId;
    private final Path pluginRoot;
    private final RuntimePermissions permissions;
    private final ShamooNodeRuntimeOptions options;
    private final RuntimeErrorReporter errorReporter;
    private final ThreadPoolExecutor executor;
    private final Object admissionLock = new Object();
    private final Object creationLock = new Object();
    private final AtomicReference<RuntimeState> state = new AtomicReference<>(RuntimeState.CREATING);
    private final AtomicInteger activeInvocations = new AtomicInteger();
    private final AtomicLong submittedInvocations = new AtomicLong();
    private final AtomicLong completedInvocations = new AtomicLong();
    private final AtomicLong rejectedInvocations = new AtomicLong();
    private final AtomicLong unhandledErrors = new AtomicLong();
    private final ResourceRegistry resources = new ResourceRegistry();
    private final SourceMapRegistry sourceMaps = new SourceMapRegistry();
    private final Map<String, ModuleDefinition> modules = new HashMap<>();
    private final Map<String, V8Value> commonJsCache = new HashMap<>();
    private final Map<String, IV8Module> resolvedModules = new HashMap<>();
    private final Map<Long, RuntimeUnhandledError> pendingPromiseRejections = new HashMap<>();
    private final Deque<String> commonJsReferrers = new ArrayDeque<>();
    private final List<JavetCallbackContext> callbackContexts = new ArrayList<>();
    private final java.util.Queue<Runnable> isolateCompletions =
            new java.util.concurrent.ConcurrentLinkedQueue<>();
    private final CompletableFuture<Void> closeCompletion = new CompletableFuture<>();
    private volatile Thread ownerThread;
    private RuntimeCreationError creationAbort;
    private NodeRuntime nodeRuntime;
    private JavaProxyRegistry javaProxyRegistry;
    private V8ValueFunction nativeRequire;
    private V8ValueFunction controlledRequire;
    private V8ValueFunction awaitedRejectionHandler;
    private RuntimeError pendingResolutionError;

    private ShamooNodeRuntime(
            PluginId pluginId,
            Path pluginRoot,
            NodePolicy policy,
            ShamooNodeRuntimeOptions options,
            RuntimeErrorReporter errorReporter) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.pluginRoot = canonicalRoot(pluginRoot);
        permissions = RuntimePermissions.from(Objects.requireNonNull(policy, "policy"));
        this.options = Objects.requireNonNull(options, "options");
        this.errorReporter = Objects.requireNonNull(errorReporter, "errorReporter");
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "shamoo-node-" + pluginId.value());
            thread.setDaemon(true);
            return thread;
        };
        executor = new ThreadPoolExecutor(
            1,
            1,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(options.queueCapacity()),
            threadFactory,
            new ThreadPoolExecutor.AbortPolicy());
    }

    public static ShamooNodeRuntime create(
            PluginId pluginId,
            Path pluginRoot,
            NodePolicy policy,
            Map<String, HostFunction> hostBindings,
            ShamooNodeRuntimeOptions options,
            RuntimeErrorReporter errorReporter) {
        ShamooNodeRuntime runtime = new ShamooNodeRuntime(pluginId, pluginRoot, policy, options, errorReporter);
        CompletableFuture<Void> initialized = new CompletableFuture<>();
        runtime.executor.execute(() -> {
            try {
                runtime.initialize(hostBindings);
                synchronized (runtime.creationLock) {
                    if (runtime.creationAbort == null) {
                        runtime.state.set(RuntimeState.RUNNING);
                        initialized.complete(null);
                    } else {
                        Throwable cleanupFailure = runtime.cleanupNativeRuntime();
                        if (cleanupFailure != null) {
                            runtime.creationAbort.addSuppressed(cleanupFailure);
                        }
                        runtime.state.set(RuntimeState.CLOSED);
                        initialized.completeExceptionally(runtime.creationAbort);
                    }
                }
            } catch (Throwable throwable) {
                Throwable cleanupFailure = runtime.cleanupNativeRuntime();
                if (cleanupFailure != null) {
                    throwable.addSuppressed(cleanupFailure);
                }
                runtime.state.set(RuntimeState.FAILED);
                initialized.completeExceptionally(new RuntimeCreationError(
                    pluginId, "unable to create Node runtime for " + pluginId, throwable));
            } finally {
                if (runtime.state.get() != RuntimeState.RUNNING) {
                    runtime.executor.shutdown();
                }
            }
        });
        try {
            initialized.get(options.invocationTimeout().toMillis(), TimeUnit.MILLISECONDS);
            return runtime;
        } catch (InterruptedException exception) {
            RuntimeCreationError error = new RuntimeCreationError(
                pluginId, "interrupted while creating Node runtime", exception);
            runtime.abortCreation(error);
            runtime.awaitCreationCompletion(initialized);
            Thread.currentThread().interrupt();
            throw error;
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeCreationError creationError) {
                throw creationError;
            }
            throw new RuntimeCreationError(pluginId, "unable to create Node runtime", cause);
        } catch (TimeoutException exception) {
            RuntimeCreationError error = new RuntimeCreationError(
                pluginId, "timed out while creating Node runtime", exception);
            runtime.abortCreation(error);
            runtime.awaitCreationCompletion(initialized);
            throw error;
        }
    }

    public static ShamooNodeRuntime create(PluginId pluginId, Path pluginRoot, NodePolicy policy) {
        return create(
            pluginId,
            pluginRoot,
            policy,
            Map.of(),
            ShamooNodeRuntimeOptions.DEFAULT,
            error -> System.getLogger(ShamooNodeRuntime.class.getName()).log(System.Logger.Level.ERROR, error));
    }

    public PluginId pluginId() {
        return pluginId;
    }

    public RuntimePermissions permissions() {
        return permissions;
    }

    public RuntimeState state() {
        return state.get();
    }

    public CompletableFuture<Void> registerSourceMap(SourcePosition generated, SourcePosition original) {
        Objects.requireNonNull(generated, "generated");
        Objects.requireNonNull(original, "original");
        SourcePosition canonicalGenerated = new SourcePosition(
            canonicalModuleName(generated.resourceName()), generated.line(), generated.column());
        return submit(() -> {
            sourceMaps.register(canonicalGenerated, original);
            return null;
        });
    }

    public CompletableFuture<Object> evaluate(String source, String resourceName) {
        Objects.requireNonNull(source, "source");
        String canonicalName = canonicalModuleName(resourceName);
        return submit(() -> evaluateScript(source, canonicalName));
    }

    public CompletableFuture<Void> registerModule(String name, String source, ModuleKind kind) {
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(kind, "kind");
        String canonicalName = canonicalModuleName(name);
        return submit(() -> {
            if (modules.putIfAbsent(canonicalName, new ModuleDefinition(source, kind)) != null) {
                throw new RuntimeModuleResolutionError(pluginId, name, "module is already registered: " + name, null);
            }
            return null;
        });
    }

    public CompletableFuture<Object> executeModule(String name) {
        String canonicalName = canonicalModuleName(name);
        return submit(() -> executeRegisteredModule(canonicalName));
    }

    /** Invokes only a callback explicitly registered by JS through {@code host.registerCallback()}. */
    @SuppressWarnings("try")
    public CompletableFuture<Object> invokeCallback(String name, List<Object> arguments) {
        Objects.requireNonNull(name, "name");
        List<Object> copied = List.copyOf(arguments);
        return submit(() -> {
            assertOwnerThread();
            V8ValueFunction function = javaProxyRegistry.callback(name);
            try (V8Guard guard = invocationGuard();
                    V8Value result = function.callExtended(nodeRuntime.getGlobalObject(), true, copied.toArray())) {
                return awaitAndConvert(result);
            } catch (JavetException exception) {
                throw translateEvaluation(exception, "callback:" + name);
            }
        });
    }

    public CompletableFuture<String> readTextFile(String relativePath) {
        return submit(() -> readSecureFile(
            policyRelativePath(relativePath, permissions.readablePaths(), "read"), relativePath));
    }

    public CompletableFuture<Void> writeTextFile(String relativePath, String contents) {
        Objects.requireNonNull(contents, "contents");
        return submit(() -> {
            writeSecureFile(
                policyRelativePath(relativePath, permissions.writablePaths(), "write"), relativePath, contents);
            return null;
        });
    }

    public RuntimeMetricsSnapshot metrics() {
        JavaProxyRegistry registry = javaProxyRegistry;
        return new RuntimeMetricsSnapshot(
            state.get(),
            activeInvocations.get(),
            executor.getQueue().size(),
            submittedInvocations.get(),
            completedInvocations.get(),
            rejectedInvocations.get(),
            unhandledErrors.get(),
            resources.size(),
            registry == null ? 0 : registry.size(),
            sourceMaps.size());
    }

    @Override
    public void close() {
        if (Thread.currentThread() == ownerThread) {
            throw new IllegalStateException("Node runtime cannot be closed reentrantly from its owner thread");
        }
        synchronized (admissionLock) {
            RuntimeState previous = state.get();
            if (previous == RuntimeState.RUNNING) {
                state.set(RuntimeState.CLOSING);
                List<Runnable> canceled = new ArrayList<>();
                executor.getQueue().drainTo(canceled);
                canceled.forEach(task -> {
                    if (task instanceof PendingInvocation<?> invocation) {
                        invocation.cancel();
                    }
                });
                try {
                    executor.execute(() -> {
                        Throwable failure = cleanupNativeRuntime();
                        if (failure == null) {
                            state.set(RuntimeState.CLOSED);
                            closeCompletion.complete(null);
                        } else {
                            state.set(RuntimeState.FAILED);
                            closeCompletion.completeExceptionally(failure);
                        }
                    });
                    executor.shutdown();
                } catch (RejectedExecutionException exception) {
                    state.set(RuntimeState.FAILED);
                    closeCompletion.completeExceptionally(exception);
                }
            } else if ((previous == RuntimeState.CLOSED || previous == RuntimeState.FAILED)
                    && !closeCompletion.isDone()) {
                closeCompletion.complete(null);
            }
        }
        try {
            closeCompletion.get(
                options.closeTimeout().toMillis() + options.invocationTimeout().toMillis(),
                TimeUnit.MILLISECONDS);
            if (!executor.awaitTermination(options.closeTimeout().toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Node runtime executor did not terminate");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while closing Node runtime", exception);
        } catch (ExecutionException exception) {
            throw new IllegalStateException("unable to close Node runtime", exception.getCause());
        } catch (TimeoutException exception) {
            throw new IllegalStateException("timed out while closing Node runtime", exception);
        }
    }

    private void abortCreation(RuntimeCreationError error) {
        boolean closeRunning;
        synchronized (creationLock) {
            closeRunning = state.get() == RuntimeState.RUNNING;
            if (!closeRunning) {
                creationAbort = error;
            }
        }
        if (closeRunning) {
            try {
                close();
            } catch (RuntimeException closeFailure) {
                error.addSuppressed(closeFailure);
            }
        }
    }

    private void awaitCreationCompletion(CompletableFuture<Void> initialized) {
        boolean interrupted = false;
        while (!initialized.isDone()) {
            try {
                initialized.get();
            } catch (InterruptedException exception) {
                interrupted = true;
            } catch (ExecutionException exception) {
                break;
            }
        }
        if (interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    private static Path canonicalRoot(Path root) {
        Objects.requireNonNull(root, "pluginRoot");
        try {
            return root.toRealPath();
        } catch (IOException exception) {
            throw new IllegalArgumentException("plugin root must exist: " + root, exception);
        }
    }

    @SuppressWarnings("try")
    private void initialize(Map<String, HostFunction> hostBindings) throws JavetException {
        ownerThread = Thread.currentThread();
        NodeRuntimeOptions nodeOptions = new NodeRuntimeOptions().setBuiltInModuleResolution(false);
        nodeRuntime = V8Host.getNodeInstance().createV8Runtime(nodeOptions);
        try (V8Guard guard = invocationGuard()) {
            nodeRuntime.allowEval(false);
            nodeRuntime.setV8ModuleResolver(this::resolveModule);
            nodeRuntime.setPromiseRejectCallback(this::handlePromiseRejection);
            nativeRequire = nodeRuntime.getGlobalObject().get(NodeRuntime.FUNCTION_REQUIRE);
            resources.register(nativeRequire);
            controlledRequire = createDirectFunction("require", this::requireCallback);
            resources.register(controlledRequire);
            awaitedRejectionHandler = nodeRuntime.getExecutor("(error) => undefined").execute();
            resources.register(awaitedRejectionHandler);
            installUncaughtExceptionReporter();
            javaProxyRegistry = resources.register(new JavaProxyRegistry(nodeRuntime, isolateCompletions::add));
            for (Map.Entry<String, HostFunction> binding : Map.copyOf(hostBindings).entrySet()) {
                javaProxyRegistry.register(binding.getKey(), binding.getValue());
            }
            nodeRuntime.getExecutor("Object.setPrototypeOf(host, null); Object.freeze(host);"
                    + "Object.defineProperty(globalThis, 'host', {value: host, writable: false, configurable: false});")
                    .executeVoid();
            removeUnsafeGlobals();
        }
    }

    private void installUncaughtExceptionReporter() throws JavetException {
        V8ValueFunction callback = resources.register(createDirectFunction("__shamooUncaught", arguments -> {
            String message = arguments == null || arguments.length == 0
                ? "uncaught Node exception" : errorText(arguments[0]);
            reportUnhandled(new RuntimeUnhandledError(pluginId, message, valueStack(arguments), null));
            return nodeRuntime.createV8ValueUndefined();
        }));
        nodeRuntime.getGlobalObject().set("__shamooUncaught", callback);
        nodeRuntime.getExecutor("process.on('uncaughtException', __shamooUncaught);").executeVoid();
        nodeRuntime.getGlobalObject().delete("__shamooUncaught");
    }

    private void removeUnsafeGlobals() throws JavetException {
        String[] names = {
            "require", "process", "module", "exports", "__filename", "__dirname", "fetch", "WebSocket", "EventSource",
            "BroadcastChannel", "MessageChannel", "MessageEvent", "MessagePort", "Worker", "SharedWorker",
            "Java", "Packages", "Polyglot", "load", "loadWithNewGlobal", "quit", "gc",
            "java", "javax", "com", "org", "edu"
        };
        for (String name : names) {
            nodeRuntime.getGlobalObject().delete(name);
        }
        if (!permissions.permitsBuiltin("node:buffer")) {
            nodeRuntime.getGlobalObject().delete("Buffer");
        }
    }

    private V8ValueFunction createDirectFunction(
            String name,
            IJavetDirectCallable.NoThisAndResult<Exception> callback) throws JavetException {
        JavetCallbackContext context = new JavetCallbackContext(
            name, JavetCallbackType.DirectCallNoThisAndResult, callback);
        callbackContexts.add(context);
        return nodeRuntime.createV8ValueFunction(context);
    }

    private V8Value requireCallback(V8Value... arguments) throws JavetException {
        if (arguments == null || arguments.length != 1) {
            return nodeRuntime.createV8ValueUndefined();
        }
        String specifier = arguments[0].asString();
        if (isBuiltin(specifier)) {
            return requireBuiltin(specifier);
        }
        String canonical = resolveCommonJsName(specifier);
        ModuleDefinition definition = modules.get(canonical);
        if (definition == null || definition.kind() != ModuleKind.COMMON_JS) {
            pendingResolutionError = new RuntimeModuleResolutionError(
                pluginId, specifier, "CommonJS module is not registered: " + specifier, null);
            nodeRuntime.throwError(pendingResolutionError.getMessage());
            return nodeRuntime.createV8ValueUndefined();
        }
        return executeCommonJs(canonical, definition.source());
    }

    private V8Value requireBuiltin(String requestedName) throws JavetException {
        String canonical = canonicalBuiltin(requestedName);
        if (!permissions.permitsBuiltin(canonical)) {
            RuntimePermissionError error = new RuntimePermissionError(
                pluginId, "module", canonical, "builtin is not allowed by plugin policy: " + canonical);
            pendingResolutionError = error;
            nodeRuntime.throwError(error.getMessage());
            return nodeRuntime.createV8ValueUndefined();
        }
        String family = builtinFamily(canonical);
        if (ALWAYS_DENIED_BUILTINS.contains(family) || !SAFE_BUILTINS.contains(family)) {
            RuntimePermissionError error = new RuntimePermissionError(
                pluginId,
                "module",
                canonical,
                "builtin cannot be securely mediated in-process and is disabled: " + canonical);
            pendingResolutionError = error;
            nodeRuntime.throwError(error.getMessage());
            return nodeRuntime.createV8ValueUndefined();
        }
        return nativeRequire.callExtended(nodeRuntime.getGlobalObject(), true, canonical);
    }

    private IV8Module resolveModule(
            com.caoccao.javet.interop.V8Runtime ignored,
            String resourceName,
            IV8Module referrer) throws JavetException {
        assertOwnerThread();
        if (isBuiltin(resourceName)) {
            try (V8Value moduleObject = requireBuiltin(resourceName)) {
                if (pendingResolutionError != null) {
                    return null;
                }
                if (moduleObject instanceof V8ValueObject object) {
                    object.set("default", object);
                    String canonical = canonicalBuiltin(resourceName);
                    IV8Module module = nodeRuntime.createV8Module(canonical, object);
                    resolvedModules.put(canonical, module);
                    return module;
                }
                return null;
            }
        }
        String canonical;
        try {
            canonical = resolveModuleName(resourceName, referrer);
        } catch (RuntimeError error) {
            pendingResolutionError = error;
            return null;
        }
        ModuleDefinition definition = modules.get(canonical);
        if (definition == null || definition.kind() != ModuleKind.ESM) {
            pendingResolutionError = new RuntimeModuleResolutionError(
                pluginId, resourceName, "ES module is not registered: " + resourceName, null);
            return null;
        }
        IV8Module module = executor(definition.source(), canonical, true).compileV8Module();
        resolvedModules.put(canonical, module);
        return module;
    }

    @SuppressWarnings("try")
    private Object evaluateScript(String source, String resourceName) {
        assertOwnerThread();
        pendingResolutionError = null;
        try (V8Guard guard = invocationGuard();
                V8Value result = executor(source, resourceName, false).execute()) {
            Object converted = awaitAndConvert(result);
            nodeRuntime.await(V8AwaitMode.RunNoWait);
            reportPendingPromiseRejections();
            return converted;
        } catch (JavetException exception) {
            throw translateEvaluation(exception, resourceName);
        }
    }

    @SuppressWarnings("try")
    private Object executeRegisteredModule(String canonicalName) {
        assertOwnerThread();
        ModuleDefinition definition = modules.get(canonicalName);
        if (definition == null) {
            throw new RuntimeModuleResolutionError(
                pluginId, canonicalName, "module is not registered: " + canonicalName, null);
        }
        if (definition.kind() == ModuleKind.COMMON_JS) {
            pendingResolutionError = null;
            try (V8Guard guard = invocationGuard()) {
                Object converted = awaitAndConvert(executeCommonJs(canonicalName, definition.source()));
                nodeRuntime.await(V8AwaitMode.RunNoWait);
                reportPendingPromiseRejections();
                return converted;
            } catch (JavetException exception) {
                if (pendingResolutionError != null) {
                    RuntimeError error = pendingResolutionError;
                    pendingResolutionError = null;
                    throw error;
                }
                throw translateEvaluation(exception, canonicalName);
            }
        }
        pendingResolutionError = null;
        try (V8Guard guard = invocationGuard();
                V8Module module = executor(definition.source(), canonicalName, true).compileV8Module();
                V8Value result = module.execute(true)) {
            return awaitAndConvert(result);
        } catch (JavetException exception) {
            if (pendingResolutionError != null) {
                RuntimeError error = pendingResolutionError;
                pendingResolutionError = null;
                throw error;
            }
            throw translateEvaluation(exception, canonicalName);
        }
    }

    @SuppressWarnings("try")
    private V8Value executeCommonJs(String canonicalName, String source) throws JavetException {
        V8Value cached = commonJsCache.get(canonicalName);
        if (cached != null) {
            return cached;
        }
        V8ValueObject exports = nodeRuntime.createV8ValueObject();
        V8ValueObject module = nodeRuntime.createV8ValueObject();
        module.set("exports", exports);
        commonJsCache.put(canonicalName, exports);
        String wrapped = "(function(exports, module, require, __filename, __dirname) {'use strict';\n"
            + source + "\n})";
        String directoryName = commonJsDirectory(canonicalName);
        commonJsReferrers.push(canonicalName);
        try (V8ValueFunction function = executor(wrapped, canonicalName, false).execute();
                V8Value ignored = function.callExtended(nodeRuntime.getGlobalObject(), true,
                    exports, module, controlledRequire, canonicalName, directoryName)) {
            V8Value finalExports = module.get("exports");
            if (finalExports != exports) {
                exports.close();
                commonJsCache.put(canonicalName, finalExports);
            }
            module.close();
            return finalExports;
        } catch (JavetException exception) {
            commonJsCache.remove(canonicalName);
            exports.close();
            module.close();
            if (pendingResolutionError != null) {
                RuntimeError error = pendingResolutionError;
                pendingResolutionError = null;
                throw error;
            }
            throw exception;
        } finally {
            commonJsReferrers.pop();
        }
    }

    @SuppressWarnings("try")
    private Object awaitAndConvert(V8Value result) throws JavetException {
        if (!(result instanceof V8ValuePromise promise)) {
            return nodeRuntime.toObject(result);
        }
        pendingPromiseRejections.remove(promise.getHandle());
        try (V8ValuePromise ignored = promise._catch(awaitedRejectionHandler)) {
            // The native Node rejection tracker requires a real JavaScript rejection handler.
        }
        while (promise.isPending()) {
            drainIsolateCompletions();
            nodeRuntime.await(V8AwaitMode.RunOnce);
        }
        drainIsolateCompletions();
        try (V8Value promiseResult = promise.getResult()) {
            if (promise.isRejected()) {
                throw new RuntimeEvaluationError(
                    pluginId, "promise rejected: " + errorText(promiseResult), null, valueStack(promiseResult), null);
            }
            return nodeRuntime.toObject(promiseResult);
        }
    }

    private void drainIsolateCompletions() {
        Runnable completion = isolateCompletions.poll();
        while (completion != null) {
            completion.run();
            completion = isolateCompletions.poll();
        }
    }

    private IV8Executor executor(String source, String resourceName, boolean module) {
        IV8Executor scriptExecutor = nodeRuntime.getExecutor(source);
        scriptExecutor.getV8ScriptOrigin().setResourceName(resourceName).setModule(module);
        return scriptExecutor;
    }

    private void handlePromiseRejection(
            JavetPromiseRejectEvent event,
            V8ValuePromise promise,
            V8Value value) {
        if (event == JavetPromiseRejectEvent.PromiseRejectWithNoHandler) {
            pendingPromiseRejections.put(promise.getHandle(), new RuntimeUnhandledError(
                pluginId, "unhandled promise rejection: " + errorText(value), valueStack(value), null));
        } else if (event == JavetPromiseRejectEvent.PromiseHandlerAddedAfterReject) {
            pendingPromiseRejections.remove(promise.getHandle());
        }
    }

    private void reportPendingPromiseRejections() {
        List<RuntimeUnhandledError> errors = List.copyOf(pendingPromiseRejections.values());
        pendingPromiseRejections.clear();
        errors.forEach(this::reportUnhandled);
    }

    private void reportUnhandled(RuntimeUnhandledError error) {
        unhandledErrors.incrementAndGet();
        try {
            errorReporter.report(error);
        } catch (RuntimeException reporterFailure) {
            error.addSuppressed(reporterFailure);
        }
    }

    private RuntimeEvaluationError translateEvaluation(JavetException exception, String fallbackResource) {
        SourcePosition position = null;
        String stack = null;
        String message = exception.getMessage();
        if (exception instanceof BaseJavetScriptingException scriptingException) {
            JavetScriptingError scriptingError = scriptingException.getScriptingError();
            message = scriptingError.getDetailedMessage();
                stack = sourceMaps.mapStack(scriptingError.getStack());
            if (scriptingError.getLineNumber() >= 1) {
                String resource = scriptingError.getResourceName() == null
                    ? fallbackResource : scriptingError.getResourceName();
                resource = canonicalModuleName(resource);
                int line = scriptingError.getLineNumber();
                ModuleDefinition definition = modules.get(resource);
                if (definition != null && definition.kind() == ModuleKind.COMMON_JS && line > 1) {
                    line--;
                }
                int column = Math.max(1, scriptingError.getStartColumn() + 1);
                position = sourceMaps.map(new SourcePosition(resource, line, column));
            }
        }
        return new RuntimeEvaluationError(pluginId, message, position, stack, exception);
    }

    private V8Guard invocationGuard() {
        return nodeRuntime.getGuard(options.invocationTimeout().toMillis());
    }

    private Path policyRelativePath(String requested, Set<String> allowlist, String operation) {
        Objects.requireNonNull(requested, "relativePath");
        if (requested.indexOf('\0') >= 0 || requested.contains("\\")) {
            throw deniedPath(operation, requested);
        }
        Path relative;
        try {
            relative = Path.of(requested).normalize();
        } catch (InvalidPathException exception) {
            throw deniedPath(operation, requested);
        }
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw deniedPath(operation, requested);
        }
        String normalized = relative.toString().replace('\\', '/');
        boolean allowed = allowlist.stream().anyMatch(entry -> {
            String rule = entry.startsWith("./") ? entry.substring(2) : entry;
            return rule.isEmpty() || normalized.equals(rule) || normalized.startsWith(rule + "/");
        });
        if (!allowed) {
            throw deniedPath(operation, requested);
        }
        return relative;
    }

    private String readSecureFile(Path relative, String requested) {
        try (SecureDirectoryStream<Path> root = openSecurePluginRoot();
                SecurePath securePath = openSecureParent(root, relative);
                SeekableByteChannel channel = securePath.parent().newByteChannel(
                    securePath.fileName(), Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            ByteArrayOutputStream contents = new ByteArrayOutputStream();
            ByteBuffer buffer = ByteBuffer.allocate(8192);
            while (channel.read(buffer) >= 0) {
                buffer.flip();
                contents.write(buffer.array(), 0, buffer.remaining());
                buffer.clear();
            }
            return contents.toString(StandardCharsets.UTF_8);
        } catch (IOException | UnsupportedOperationException exception) {
            throw deniedPath("read", requested);
        }
    }

    private void writeSecureFile(Path relative, String requested, String contents) {
        Set<OpenOption> openOptions = Set.of(
            StandardOpenOption.WRITE,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
        try (SecureDirectoryStream<Path> root = openSecurePluginRoot();
                SecurePath securePath = openSecureParent(root, relative);
                SeekableByteChannel channel = securePath.parent().newByteChannel(
                    securePath.fileName(), openOptions)) {
            ByteBuffer buffer = StandardCharsets.UTF_8.encode(contents);
            while (buffer.hasRemaining()) {
                channel.write(buffer);
            }
        } catch (IOException | UnsupportedOperationException exception) {
            throw deniedPath("write", requested);
        }
    }

    @SuppressWarnings("unchecked")
    private SecureDirectoryStream<Path> openSecurePluginRoot() throws IOException {
        Path filesystemRoot = Objects.requireNonNull(pluginRoot.getRoot(), "plugin root filesystem");
        DirectoryStream<Path> initial = Files.newDirectoryStream(filesystemRoot);
        if (!(initial instanceof SecureDirectoryStream<?>)) {
            initial.close();
            throw new UnsupportedOperationException("secure directory streams are unavailable");
        }
        SecureDirectoryStream<Path> current = (SecureDirectoryStream<Path>) initial;
        try {
            for (Path component : filesystemRoot.relativize(pluginRoot)) {
                SecureDirectoryStream<Path> next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                current.close();
                current = next;
            }
            return current;
        } catch (IOException | RuntimeException throwable) {
            try {
                current.close();
            } catch (IOException closeFailure) {
                throwable.addSuppressed(closeFailure);
            }
            throw throwable;
        }
    }

    private SecurePath openSecureParent(SecureDirectoryStream<Path> root, Path relative) throws IOException {
        Path fileName = relative.getFileName();
        if (fileName == null) {
            throw new IOException("filesystem target must name a file");
        }
        SecureDirectoryStream<Path> current = root;
        boolean closeCurrent = false;
        try {
            Path parent = relative.getParent();
            if (parent != null) {
                for (Path component : parent) {
                    SecureDirectoryStream<Path> next = current.newDirectoryStream(component, LinkOption.NOFOLLOW_LINKS);
                    if (closeCurrent) {
                        current.close();
                    }
                    current = next;
                    closeCurrent = true;
                }
            }
            return new SecurePath(current, fileName, closeCurrent);
        } catch (IOException | RuntimeException throwable) {
            if (closeCurrent) {
                try {
                    current.close();
                } catch (IOException closeFailure) {
                    throwable.addSuppressed(closeFailure);
                }
            }
            throw throwable;
        }
    }

    private RuntimePermissionError deniedPath(String operation, String path) {
        return new RuntimePermissionError(
            pluginId, "filesystem." + operation, path, "filesystem path is not allowed: " + path);
    }

    private String resolveModuleName(String resourceName, IV8Module referrer) throws JavetException {
        if (resourceName.startsWith(PLUGIN_PREFIX)) {
            return canonicalModuleName(resourceName.substring(PLUGIN_PREFIX.length()));
        }
        if (resourceName.startsWith("./") || resourceName.startsWith("../")) {
            String referrerName = referrer == null ? PLUGIN_PREFIX : referrer.getResourceName();
            int slash = referrerName.lastIndexOf('/');
            String parent = slash < 0 ? "" : referrerName.substring(PLUGIN_PREFIX.length(), slash + 1);
            return canonicalModuleName(parent + resourceName);
        }
        return canonicalModuleName(resourceName);
    }

    private String resolveCommonJsName(String specifier) {
        if (!(specifier.startsWith("./") || specifier.startsWith("../"))) {
            return canonicalModuleName(specifier);
        }
        String referrer = commonJsReferrers.peek();
        if (referrer == null) {
            throw new RuntimeModuleResolutionError(
                pluginId, specifier, "relative require has no CommonJS referrer: " + specifier, null);
        }
        String parent = commonJsDirectory(referrer);
        return canonicalModuleName(parent + (parent.endsWith("/") ? "" : "/") + specifier);
    }

    private static String commonJsDirectory(String canonicalName) {
        int slash = canonicalName.lastIndexOf('/');
        return slash <= PLUGIN_PREFIX.length() - 1 ? PLUGIN_PREFIX : canonicalName.substring(0, slash);
    }

    private String canonicalModuleName(String name) {
        Objects.requireNonNull(name, "moduleName");
        String raw = name.startsWith(PLUGIN_PREFIX) ? name.substring(PLUGIN_PREFIX.length()) : name;
        Path rawPath;
        try {
            rawPath = Path.of(raw);
        } catch (InvalidPathException exception) {
            throw new RuntimeModuleResolutionError(pluginId, name, "invalid virtual module name: " + name, exception);
        }
        if (raw.isBlank() || raw.contains("\\") || raw.indexOf('\0') >= 0 || rawPath.isAbsolute()) {
            throw new RuntimeModuleResolutionError(pluginId, name, "invalid virtual module name: " + name, null);
        }
        Path normalized = rawPath.normalize();
        if (normalized.startsWith("..") || raw.contains(":")) {
            throw new RuntimeModuleResolutionError(pluginId, name, "module traversal is denied: " + name, null);
        }
        return PLUGIN_PREFIX + normalized.toString().replace('\\', '/');
    }

    private static boolean isBuiltin(String name) {
        String canonical = canonicalBuiltin(name);
        return name.startsWith("node:")
            || SAFE_BUILTINS.contains(builtinFamily(canonical))
            || ALWAYS_DENIED_BUILTINS.contains(builtinFamily(canonical));
    }

    private static String canonicalBuiltin(String name) {
        return name.startsWith("node:") ? name : "node:" + name;
    }

    private static String builtinFamily(String canonical) {
        int separator = canonical.indexOf('/', "node:".length());
        return separator < 0 ? canonical : canonical.substring(0, separator);
    }

    private String errorText(V8Value value) {
        try {
            if (value instanceof V8ValueError error) {
                return error.getMessage();
            }
            return value.asString();
        } catch (JavetException exception) {
            return "unavailable error value";
        }
    }

    private String valueStack(V8Value... values) {
        if (values == null || values.length == 0 || !(values[0] instanceof V8ValueObject object)) {
            return null;
        }
        try {
            return sourceMaps.mapStack(object.getString("stack"));
        } catch (JavetException exception) {
            return null;
        }
    }

    private <T> CompletableFuture<T> submit(OwnerCallable<T> callable) {
        CompletableFuture<T> future = new CompletableFuture<>();
        synchronized (admissionLock) {
            if (state.get() != RuntimeState.RUNNING) {
                future.completeExceptionally(new RuntimeDisposedError(pluginId));
                return future;
            }
            try {
                executor.execute(new PendingInvocation<>(callable, future));
                submittedInvocations.incrementAndGet();
            } catch (RejectedExecutionException exception) {
                rejectedInvocations.incrementAndGet();
                future.completeExceptionally(state.get() == RuntimeState.RUNNING
                    ? new RuntimeQueueFullError(pluginId) : new RuntimeDisposedError(pluginId));
            }
        }
        return future;
    }

    private void assertOwnerThread() {
        if (Thread.currentThread() != ownerThread) {
            throw new IllegalStateException("Node runtime accessed outside its owning thread");
        }
    }

    private Throwable cleanupNativeRuntime() {
        assertOwnerThread();
        Throwable failure = null;
        if (nodeRuntime == null) {
            return null;
        }
        failure = cleanupStep(failure, () -> nodeRuntime.setStopping(true));
        for (V8Value exports : commonJsCache.values()) {
            failure = cleanupStep(failure, exports::close);
        }
        commonJsCache.clear();
        commonJsReferrers.clear();
        pendingPromiseRejections.clear();
        modules.clear();
        sourceMaps.clear();
        failure = cleanupStep(failure, resources::closeAll);
        for (JavetCallbackContext context : callbackContexts) {
            failure = cleanupStep(failure, () -> nodeRuntime.removeCallbackContext(context.getHandle()));
        }
        callbackContexts.clear();
        for (IV8Module module : resolvedModules.values()) {
            failure = cleanupStep(failure, () -> nodeRuntime.removeV8Module(module, true));
        }
        resolvedModules.clear();
        failure = cleanupStep(failure, () -> nodeRuntime.removeV8Modules(true));
        failure = cleanupStep(failure, () -> nodeRuntime.await(V8AwaitMode.RunNoWait));
        failure = cleanupStep(failure, () -> nodeRuntime.close(true));
        return failure;
    }

    private static Throwable cleanupStep(Throwable failure, CleanupAction action) {
        try {
            action.run();
        } catch (Throwable throwable) {
            if (failure == null) {
                return throwable;
            }
            failure.addSuppressed(throwable);
        }
        return failure;
    }

    @FunctionalInterface
    private interface OwnerCallable<T> {
        T call() throws Exception;
    }

    @FunctionalInterface
    private interface CleanupAction {
        void run() throws Throwable;
    }

    private final class PendingInvocation<T> implements Runnable {
        private final OwnerCallable<T> callable;
        private final CompletableFuture<T> future;

        private PendingInvocation(OwnerCallable<T> callable, CompletableFuture<T> future) {
            this.callable = callable;
            this.future = future;
        }

        @Override
        public void run() {
            activeInvocations.incrementAndGet();
            try {
                assertOwnerThread();
                future.complete(callable.call());
            } catch (Throwable throwable) {
                future.completeExceptionally(throwable);
            } finally {
                activeInvocations.decrementAndGet();
                completedInvocations.incrementAndGet();
            }
        }

        private void cancel() {
            future.completeExceptionally(new RuntimeDisposedError(pluginId));
            completedInvocations.incrementAndGet();
        }
    }

    private record ModuleDefinition(String source, ModuleKind kind) {
    }

    private record SecurePath(
            SecureDirectoryStream<Path> parent,
            Path fileName,
            boolean closeParent) implements AutoCloseable {
        @Override
        public void close() throws IOException {
            if (closeParent) {
                parent.close();
            }
        }
    }
}
