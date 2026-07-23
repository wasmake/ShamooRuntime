package dev.shamoo.runtime.javet;

import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.interop.NodeRuntime;
import com.caoccao.javet.interop.callback.IJavetDirectCallable;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.JavetCallbackType;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Registry of explicit direct callbacks. It never enables reflective Java proxy conversion. */
@SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.CloseResource"})
public final class JavaProxyRegistry implements AutoCloseable {
    private final Map<String, RegisteredFunction> functions = new LinkedHashMap<>();
    private final NodeRuntime runtime;
    private final V8ValueObject hostObject;

    JavaProxyRegistry(NodeRuntime runtime) throws JavetException {
        this.runtime = Objects.requireNonNull(runtime, "runtime");
        hostObject = runtime.createV8ValueObject();
        runtime.getGlobalObject().set("host", hostObject);
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
                    converted.add(runtime.toObject(argument));
                }
            }
            return runtime.toV8Value(hostFunction.invoke(List.copyOf(converted)));
        };
        JavetCallbackContext context = new JavetCallbackContext(
            name, JavetCallbackType.DirectCallNoThisAndResult, callback);
        V8ValueFunction function = runtime.createV8ValueFunction(context);
        functions.put(name, new RegisteredFunction(function, context));
        hostObject.set(name, function);
    }

    public synchronized int size() {
        return functions.size();
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
