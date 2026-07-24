package dev.shamoo.runtime.javet;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.callback.IJavetDirectCallable;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.JavetCallbackType;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import com.caoccao.javet.values.reference.V8ValuePromise;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletionStage;
import java.util.function.Consumer;

/** Registry of explicit direct callbacks. It never enables reflective Java proxy conversion. */
@SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CloseResource", "PMD.UseTryWithResources"})
public final class JavaProxyRegistry implements AutoCloseable {
    private final Map<String, RegisteredFunction> functions = new LinkedHashMap<>();
    private final Map<String, V8ValueFunction> callbacks = new LinkedHashMap<>();
    private final NodeRuntime runtime;
    private final V8ValueObject hostObject;
    private final Consumer<Runnable> isolateCompletion;

    JavaProxyRegistry(NodeRuntime runtime, Consumer<Runnable> isolateCompletion) throws JavetException {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        this.isolateCompletion = Objects.requireNonNull(isolateCompletion, "isolateCompletion");
        hostObject = runtime.createV8ValueObject();
        runtime.getGlobalObject().set("host", hostObject);
        registerCallbackOperation();
    }

    public synchronized void register(String name, HostFunction hostFunction) throws JavetException {
        if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
            throw new IllegalArgumentException("invalid host binding name: " + name);
        }
        Objects.requireNonNull(hostFunction, "hostFunction");
        if (functions.containsKey(name)) {
            throw new IllegalArgumentException("duplicate host binding: " + name);
        }
        IJavetDirectCallable.NoThisAndResult<Exception> callback = arguments -> {
            int argumentCount = arguments == null ? 0 : arguments.length;
            List<Object> converted = new ArrayList<>(argumentCount);
            if (arguments != null) {
                for (V8Value argument : arguments) {
                    converted.add(requireDataValue(runtime.toObject(argument), "host argument",
                            new IdentityHashMap<>()));
                }
            }
            Object result = hostFunction.invoke(List.copyOf(converted));
            if (result instanceof CompletionStage<?> stage) {
                V8ValuePromise promise = runtime.createV8ValuePromise();
                stage.whenComplete((value, failure) -> isolateCompletion.accept(() -> {
                    try (V8Value resolved = runtime.toV8Value(failure == null
                            ? requireDataValue(value, "host promise value", new IdentityHashMap<>())
                            : failureText(failure))) {
                        if (failure == null) {
                            promise.resolve(resolved);
                        } else {
                            promise.reject(resolved);
                        }
                    } catch (JavetException | RuntimeException exception) {
                        try (V8Value rejection = runtime.toV8Value(exception.toString())) {
                            promise.reject(rejection);
                        } catch (JavetException ignored) {
                            // Runtime disposal owns the unresolved promise when conversion is impossible.
                        }
                    } finally {
                        try {
                            promise.close();
                        } catch (JavetException ignored) {
                            // Native runtime cleanup is the final disposal fallback.
                        }
                    }
                }));
                return promise.getPromise();
            }
            return runtime.toV8Value(requireDataValue(result, "host return value", new IdentityHashMap<>()));
        };
        JavetCallbackContext context = new JavetCallbackContext(
            name, JavetCallbackType.DirectCallNoThisAndResult, callback);
        V8ValueFunction function = runtime.createV8ValueFunction(context);
        functions.put(name, new RegisteredFunction(function, context));
        hostObject.set(name, function);
    }

    private static String failureText(Throwable failure) {
        Throwable current = failure;
        while ((current instanceof java.util.concurrent.CompletionException
                || current instanceof java.util.concurrent.ExecutionException) && current.getCause() != null) {
            current = current.getCause();
        }
        return current.getClass().getSimpleName() + ": " + String.valueOf(current.getMessage());
    }

    public synchronized int size() {
        return functions.size() + callbacks.size();
    }

    synchronized V8ValueFunction callback(String name) {
        V8ValueFunction callback = callbacks.get(name);
        if (callback == null) {
            throw new IllegalArgumentException("unknown JS callback: " + name);
        }
        return callback;
    }

    private void registerCallbackOperation() throws JavetException {
        IJavetDirectCallable.NoThisAndResult<Exception> callback = arguments -> {
            if (arguments == null || arguments.length != 2 || !(arguments[0] instanceof V8Value)
                    || !(arguments[1] instanceof V8ValueFunction function)) {
                throw new IllegalArgumentException("registerCallback requires a name and function");
            }
            String name = arguments[0].asString();
            if (name == null || !name.matches("[A-Za-z_$][A-Za-z0-9_$.-]*")) {
                throw new IllegalArgumentException("invalid callback name: " + name);
            }
            V8ValueFunction retained = function.toClone(true);
            V8ValueFunction previous = callbacks.putIfAbsent(name, retained);
            if (previous != null) {
                retained.close();
                throw new IllegalArgumentException("callback already registered: " + name);
            }
            return runtime.createV8ValueBoolean(true);
        };
        JavetCallbackContext context = new JavetCallbackContext(
                "registerCallback", JavetCallbackType.DirectCallNoThisAndResult, callback);
        V8ValueFunction function = runtime.createV8ValueFunction(context);
        functions.put("registerCallback", new RegisteredFunction(function, context));
        hostObject.set("registerCallback", function);
    }

    private static Object requireDataValue(Object value, String boundary, Map<Object, Boolean> visited) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Number
                || value instanceof Character || value instanceof byte[]) {
            return value;
        }
        if (value instanceof V8Value || value instanceof Class<?> || value instanceof ClassLoader
                || value instanceof java.lang.reflect.Member
                || java.lang.reflect.Proxy.isProxyClass(value.getClass())) {
            throw new IllegalArgumentException(boundary + " cannot expose Java, reflection, proxy, or Javet objects");
        }
        if (visited.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException(boundary + " must not contain cycles");
        }
        if (value instanceof List<?> list) {
            List<Object> safe = new ArrayList<>(list.size());
            for (Object item : list) {
                safe.add(requireDataValue(item, boundary, visited));
            }
            return List.copyOf(safe);
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> safe = new LinkedHashMap<>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text)) {
                    throw new IllegalArgumentException(boundary + " map keys must be strings");
                }
                safe.put(text, requireDataValue(item, boundary, visited));
            });
            return Map.copyOf(safe);
        }
        throw new IllegalArgumentException(boundary + " must contain only immutable data values");
    }

    @Override
    public synchronized void close() throws JavetException {
        Throwable failure = closeStep(null, () -> runtime.getGlobalObject().delete("host"));
        for (RegisteredFunction registered : functions.values()) {
            failure = closeStep(failure, registered.function()::close);
            failure = closeStep(failure,
                () -> runtime.removeCallbackContext(registered.context().getHandle()));
        }
        functions.clear();
        for (V8ValueFunction callback : callbacks.values()) {
            failure = closeStep(failure, callback::close);
        }
        callbacks.clear();
        failure = closeStep(failure, hostObject::close);
        if (failure instanceof JavetException javetException) {
            throw javetException;
        }
        if (failure instanceof RuntimeException runtimeException) {
            throw runtimeException;
        }
        if (failure instanceof Error error) {
            throw error;
        }
    }

    private static Throwable closeStep(Throwable failure, CloseAction action) {
        try {
            action.close();
        } catch (Throwable throwable) {
            if (failure == null) {
                return throwable;
            }
            failure.addSuppressed(throwable);
        }
        return failure;
    }

    @FunctionalInterface
    private interface CloseAction {
        void close() throws Throwable;
    }

    private record RegisteredFunction(V8ValueFunction function, JavetCallbackContext context) {
    }
}
