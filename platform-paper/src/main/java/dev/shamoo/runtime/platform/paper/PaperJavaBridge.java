package dev.shamoo.runtime.platform.paper;

import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ScriptCallback;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Executable;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.lang.invoke.MethodType;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Location;
import org.bukkit.entity.Entity;
import org.bukkit.event.Event;
import org.bukkit.event.block.BlockEvent;
import org.bukkit.event.entity.EntityEvent;
import org.bukkit.event.inventory.InventoryEvent;
import org.bukkit.event.player.PlayerEvent;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;

/** Plugin-neutral, generated-catalog-backed access to the public Paper API. */
@SuppressWarnings({"PMD.AssignmentInOperand", "PMD.AvoidCatchingThrowable", "PMD.AvoidDuplicateLiterals",
        "PMD.AvoidFieldNameMatchingMethodName", "PMD.AvoidLiteralsInIfCondition", "PMD.CloseResource",
        "PMD.CompareObjectsWithEquals", "PMD.LooseCoupling", "PMD.UseProperClassLoader", "PMD.UseTryWithResources",
        "PMD.UseVarargs"})
public final class PaperJavaBridge implements AutoCloseable {
    private static final String HANDLE = "$paperHandle";
    private static final String FRAME = "$paperFrame";
    private static final String OBJECT = "$paperObject";
    private static final int MAXIMUM_DEPTH = 32;
    private static final int MAXIMUM_COLLECTION = 10_000;
    private final Map<String, PaperInvocationFrame> frames = new ConcurrentHashMap<>();
    private final Map<String, Handle> handles = new ConcurrentHashMap<>();
    private final Map<Object, String> identities = new IdentityHashMap<>();
    private final Set<Object> nativeResources = Collections.newSetFromMap(new IdentityHashMap<>());
    private final Object lifecycleLock = new Object();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final GeneratedPaperApiRegistry registry;
    private final Duration synchronousTimeout;
    private final int maximumHandles;
    private final int maximumPendingFrameCalls;
    private final JavaPlugin plugin;
    private final PluginId owner;
    private final UUID generation;
    private final BooleanSupplier replacementPresent;

    public PaperJavaBridge(JavaPlugin plugin, PluginId owner, UUID generation, GeneratedPaperApiRegistry registry,
            Duration synchronousTimeout, int maximumPendingFrameCalls, int maximumHandles) {
        this(plugin, owner, generation, registry, synchronousTimeout, maximumPendingFrameCalls, maximumHandles,
                () -> false);
    }

    public PaperJavaBridge(JavaPlugin plugin, PluginId owner, UUID generation, GeneratedPaperApiRegistry registry,
            Duration synchronousTimeout, int maximumPendingFrameCalls, int maximumHandles,
            BooleanSupplier replacementPresent) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.generation = Objects.requireNonNull(generation, "generation");
        this.registry = Objects.requireNonNull(registry, "registry");
        this.replacementPresent = Objects.requireNonNull(replacementPresent, "replacementPresent");
        this.synchronousTimeout = Objects.requireNonNull(synchronousTimeout, "synchronousTimeout");
        if (maximumPendingFrameCalls < 1 || maximumHandles < 1) {
            throw new IllegalArgumentException("Paper API limits must be positive");
        }
        this.maximumPendingFrameCalls = maximumPendingFrameCalls;
        this.maximumHandles = maximumHandles;
    }

    public PluginId owner() {
        return owner;
    }

    public UUID generation() {
        return generation;
    }

    public boolean open() {
        return !closed.get();
    }

    /** Invokes one exact or overload-resolved generated Paper operation. */
    public Object invoke(List<Object> arguments) {
        requireOpen();
        if (arguments.size() != 1 || !(arguments.getFirst() instanceof Map<?, ?> request)) {
            throw new IllegalArgumentException("paperJava requires exactly one request object");
        }
        String operation = text(request, "operation");
        return switch (operation) {
            case "construct" -> construct(request);
            case "invoke" -> invokeMethod(request);
            case "get" -> getField(request);
            case "set" -> setField(request);
            case "release" -> release(request);
            case "describe" -> Map.of("owner", owner.value(), "generation", generation.toString(),
                    "members", registry.memberCount(), "handles", handles.size(),
                    "replacementPresent", replacementPresent.getAsBoolean());
            default -> throw new IllegalArgumentException("unknown paperJava operation: " + operation);
        };
    }

    /** Delivers a live generated event handle and services API calls on the event's origin thread. */
    public void dispatchEvent(Event event, ScriptCallback callback) throws Exception {
        requireOpen();
        Objects.requireNonNull(event, "event");
        Objects.requireNonNull(callback, "callback");
        PaperInvocationFrame frame = createFrame();
        try {
            Object marker = encodeHandle(event, event.getClass().getName(), frame, affinity(event, null));
            frame.await(callback.invoke(List.of(marker)));
        } finally {
            expire(frame);
        }
    }

    private Object construct(Map<?, ?> request) {
        String ownerName = text(request, "type");
        List<?> values = values(request, "arguments");
        ResolvedMember resolved = resolve(ownerName, GeneratedPaperApiRegistry.Kind.CONSTRUCTOR, "<init>",
                optionalText(request, "descriptor"), values);
        Constructor<?> constructor = constructor(resolved.member());
        Object[] converted = convertArguments(values, constructor.getParameterTypes(), constructor.isVarArgs(), false);
        return execute(request, null, null, () -> invokeConstructor(constructor, converted));
    }

    private Object invokeMethod(Map<?, ?> request) {
        String ownerName = text(request, "type");
        String name = text(request, "name");
        List<?> values = values(request, "arguments");
        ResolvedMember resolved = resolve(ownerName, GeneratedPaperApiRegistry.Kind.METHOD, name,
                optionalText(request, "descriptor"), values);
        Method method = method(resolved.member());
        Handle target = target(request, Modifier.isStatic(method.getModifiers()));
        boolean oneShot = oneShotSchedulerCallback(resolved.member());
        boolean hasCallbacks = containsCallback(values);
        Object[] converted = convertArguments(values, method.getParameterTypes(), method.isVarArgs(), oneShot);
        Object executionAffinity = target == null ? null : target.affinity();
        if (executionAffinity == null) {
            executionAffinity = argumentAffinity(converted);
        }
        return execute(request, target, executionAffinity, () -> registerSchedulerResult(
                resolved.member(), invokeScheduledMethod(resolved.member(), method,
                        target == null ? null : target.value(), converted),
                hasCallbacks));
    }

    @SuppressWarnings("unchecked")
    private Object invokeScheduledMethod(GeneratedPaperApiRegistry.Member member, Method method,
            Object target, Object[] arguments) throws Exception {
        if (!"org.bukkit.scheduler.BukkitScheduler".equals(member.owner())
                || !method.getReturnType().equals(void.class)
                || !member.descriptor().contains("Ljava/util/function/Consumer;")) {
            return invokeMethod(method, target, arguments);
        }
        BukkitScheduler scheduler = (BukkitScheduler) target;
        Plugin taskPlugin = (Plugin) arguments[0];
        Consumer<BukkitTask> callback = (Consumer<BukkitTask>) arguments[1];
        CompletableFuture<BukkitTask> registered = new CompletableFuture<>();
        Runnable action = () -> callback.accept(registered.join());
        BukkitTask task = switch (member.name()) {
            case "runTask" -> scheduler.runTask(taskPlugin, action);
            case "runTaskAsynchronously" -> scheduler.runTaskAsynchronously(taskPlugin, action);
            case "runTaskLater" -> scheduler.runTaskLater(taskPlugin, action, ((Number) arguments[2]).longValue());
            case "runTaskLaterAsynchronously" -> scheduler.runTaskLaterAsynchronously(
                    taskPlugin, action, ((Number) arguments[2]).longValue());
            case "runTaskTimer" -> scheduler.runTaskTimer(taskPlugin, action,
                    ((Number) arguments[2]).longValue(), ((Number) arguments[3]).longValue());
            case "runTaskTimerAsynchronously" -> scheduler.runTaskTimerAsynchronously(taskPlugin, action,
                    ((Number) arguments[2]).longValue(), ((Number) arguments[3]).longValue());
            default -> throw new IllegalStateException("unknown void Bukkit scheduler callback: " + member.id());
        };
        registered.complete(task);
        return task;
    }

    private Object getField(Map<?, ?> request) {
        String ownerName = text(request, "type");
        String name = text(request, "name");
        GeneratedPaperApiRegistry.Member member = field(ownerName, name, optionalText(request, "descriptor"));
        Field field = field(member);
        Handle target = target(request, Modifier.isStatic(field.getModifiers()));
        return execute(request, target, target == null ? null : target.affinity(),
                () -> field.get(target == null ? null : target.value()));
    }

    private Object setField(Map<?, ?> request) {
        String ownerName = text(request, "type");
        String name = text(request, "name");
        GeneratedPaperApiRegistry.Member member = field(ownerName, name, optionalText(request, "descriptor"));
        if (member.readOnly()) {
            throw new IllegalArgumentException("generated Paper field is read-only: " + member.id());
        }
        Field field = field(member);
        Handle target = target(request, Modifier.isStatic(field.getModifiers()));
        Object converted = convert(request.get("value"), field.getType(), 0, false);
        return execute(request, target, target == null ? null : target.affinity(), () -> {
            field.set(target == null ? null : target.value(), converted);
            return null;
        });
    }

    private Object release(Map<?, ?> request) {
        String id = text(request, "handle");
        synchronized (lifecycleLock) {
            Handle removed = handles.remove(id);
            if (removed != null) {
                forgetIdentity(removed);
            }
            return removed != null;
        }
    }

    private Object execute(Map<?, ?> request, Handle target, Object inheritedAffinity, CheckedAction action) {
        PaperInvocationFrame frame = requestFrame(request, target);
        CompletionStage<Object> raw;
        if (frame != null) {
            raw = frame.call(action::run);
        } else {
            raw = schedule(inheritedAffinity, action);
        }
        return raw.thenCompose(PaperJavaBridge::flatten).thenApply(value -> {
            try {
                return encode(value, null, affinity(value, inheritedAffinity), new EncodeState(), 0);
            } catch (RuntimeException | Error failure) {
                discardNativeResource(value);
                throw failure;
            }
        });
    }

    private CompletionStage<Object> schedule(Object affinity, CheckedAction action) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        Runnable execute = () -> {
            if (closed.get()) {
                result.completeExceptionally(new IllegalStateException("Paper API bridge is closed"));
                return;
            }
            try {
                result.complete(action.run());
            } catch (Throwable failure) {
                result.completeExceptionally(failure);
            }
        };
        if (affinity instanceof Entity entity) {
            if (plugin.getServer().isOwnedByCurrentRegion(entity)) {
                execute.run();
                return result;
            }
            if (entity.getScheduler().run(plugin, ignored -> execute.run(),
                    () -> result.completeExceptionally(new IllegalStateException("Paper entity is retired"))) == null) {
                result.completeExceptionally(new IllegalStateException("Paper entity is retired"));
            }
        } else if (affinity instanceof Location location && location.getWorld() != null) {
            if (plugin.getServer().isOwnedByCurrentRegion(location)) {
                execute.run();
                return result;
            }
            plugin.getServer().getRegionScheduler().run(plugin, location, ignored -> execute.run());
        } else {
            if (plugin.getServer().isGlobalTickThread()) {
                execute.run();
                return result;
            }
            plugin.getServer().getGlobalRegionScheduler().run(plugin, ignored -> execute.run());
        }
        return result;
    }

    private PaperInvocationFrame requestFrame(Map<?, ?> request, Handle target) {
        String requested = optionalText(request, "frame");
        String scoped = target == null || target.frame() == null ? null : target.frame().id();
        if (requested != null && scoped != null && !requested.equals(scoped)) {
            throw new IllegalArgumentException("Paper invocation mixes different callback frames");
        }
        if (requested != null && target != null && scoped == null) {
            throw new IllegalArgumentException("detached Paper handles cannot enter a callback frame");
        }
        String id = requested == null ? scoped : requested;
        if (id == null) {
            return null;
        }
        PaperInvocationFrame frame = frames.get(id);
        if (frame == null) {
            throw new IllegalStateException("Paper invocation frame has expired");
        }
        return frame;
    }

    private ResolvedMember resolve(String ownerName, GeneratedPaperApiRegistry.Kind kind, String name,
            String descriptor, List<?> arguments) {
        if (descriptor != null) {
            GeneratedPaperApiRegistry.Member member = registry.require(ownerName, kind, name, descriptor);
            Executable executable = kind == GeneratedPaperApiRegistry.Kind.CONSTRUCTOR
                    ? constructor(member) : method(member);
            if (!canConvert(arguments, executable.getParameterTypes(), executable.isVarArgs())) {
                throw new IllegalArgumentException("arguments do not match generated Paper member " + member.id());
            }
            return new ResolvedMember(member);
        }
        List<GeneratedPaperApiRegistry.Member> candidates = registry.overloads(ownerName, kind, name).stream()
                .filter(candidate -> {
                    Executable executable = kind == GeneratedPaperApiRegistry.Kind.CONSTRUCTOR
                            ? constructor(candidate) : method(candidate);
                    return canConvert(arguments, executable.getParameterTypes(), executable.isVarArgs());
                }).toList();
        List<GeneratedPaperApiRegistry.Member> fixedArity = candidates.stream().filter(candidate -> {
            Executable executable = kind == GeneratedPaperApiRegistry.Kind.CONSTRUCTOR
                    ? constructor(candidate) : method(candidate);
            return !executable.isVarArgs();
        }).toList();
        if (!fixedArity.isEmpty()) {
            candidates = fixedArity;
        }
        if (candidates.size() != 1) {
            throw new IllegalArgumentException(candidates.isEmpty()
                    ? "no generated Paper overload matches " + ownerName + "#" + name
                    : "ambiguous generated Paper overload; supply an exact JVM descriptor for "
                            + ownerName + "#" + name);
        }
        return new ResolvedMember(candidates.getFirst());
    }

    private GeneratedPaperApiRegistry.Member field(String ownerName, String name, String descriptor) {
        if (descriptor != null) {
            return registry.require(ownerName, GeneratedPaperApiRegistry.Kind.FIELD, name, descriptor);
        }
        List<GeneratedPaperApiRegistry.Member> candidates = registry.overloads(
                ownerName, GeneratedPaperApiRegistry.Kind.FIELD, name);
        if (candidates.size() != 1) {
            throw new IllegalArgumentException("generated Paper field requires an exact descriptor: "
                    + ownerName + "#" + name);
        }
        return candidates.getFirst();
    }

    private Constructor<?> constructor(GeneratedPaperApiRegistry.Member member) {
        try {
            Class<?> ownerType = registry.requireType(member.owner());
            Class<?>[] parameters = MethodType.fromMethodDescriptorString(member.descriptor(),
                    ownerType.getClassLoader()).parameterArray();
            return ownerType.getConstructor(parameters);
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("generated Paper constructor cannot be linked: " + member.id(),
                    exception);
        }
    }

    private Method method(GeneratedPaperApiRegistry.Member member) {
        try {
            Class<?> ownerType = registry.requireType(member.owner());
            MethodType type = MethodType.fromMethodDescriptorString(member.descriptor(), ownerType.getClassLoader());
            Method result = ownerType.getMethod(member.name(), type.parameterArray());
            if (!result.getReturnType().equals(type.returnType())) {
                throw new IllegalArgumentException("generated Paper method return type does not match: " + member.id());
            }
            return result;
        } catch (NoSuchMethodException exception) {
            throw new IllegalArgumentException("generated Paper method cannot be linked: " + member.id(), exception);
        }
    }

    private Field field(GeneratedPaperApiRegistry.Member member) {
        try {
            Class<?> ownerType = registry.requireType(member.owner());
            Field result = ownerType.getField(member.name());
            Class<?> expected = MethodType.fromMethodDescriptorString("()" + member.descriptor(),
                    ownerType.getClassLoader()).returnType();
            if (!result.getType().equals(expected)) {
                throw new IllegalArgumentException("generated Paper field type does not match: " + member.id());
            }
            return result;
        } catch (NoSuchFieldException exception) {
            throw new IllegalArgumentException("generated Paper field cannot be linked: " + member.id(), exception);
        }
    }

    private static Object invokeConstructor(Constructor<?> constructor, Object[] arguments) throws Exception {
        try {
            return constructor.newInstance(arguments);
        } catch (InvocationTargetException exception) {
            throw invocationFailure(exception);
        }
    }

    private static Object invokeMethod(Method method, Object target, Object[] arguments) throws Exception {
        try {
            return method.invoke(target, arguments);
        } catch (InvocationTargetException exception) {
            throw invocationFailure(exception);
        }
    }

    private static Exception invocationFailure(InvocationTargetException exception) throws Exception {
        Throwable cause = exception.getCause();
        if (cause instanceof Exception checked) {
            return checked;
        }
        if (cause instanceof Error error) {
            throw error;
        }
        return exception;
    }

    private Handle target(Map<?, ?> request, boolean staticMember) {
        Object marker = request.get("target");
        if (staticMember) {
            if (marker != null) {
                throw new IllegalArgumentException("static Paper member does not accept a target");
            }
            return null;
        }
        if (marker == null) {
            throw new IllegalArgumentException("instance Paper member requires a target");
        }
        return handle(marker);
    }

    private Handle handle(Object marker) {
        if (!(marker instanceof Map<?, ?> map) || !(map.get(HANDLE) instanceof String id)) {
            throw new IllegalArgumentException("Paper target must be an opaque handle");
        }
        Handle result = handles.get(id);
        if (result == null) {
            throw new IllegalStateException("unknown or expired Paper handle");
        }
        return result;
    }

    private Object[] convertArguments(List<?> values, Class<?>[] parameters, boolean varargs,
            boolean closeCallbackAfterInvocation) {
        if (!varargs) {
            if (values.size() != parameters.length) {
                throw new IllegalArgumentException("Paper member argument count does not match");
            }
            Object[] result = new Object[parameters.length];
            for (int index = 0; index < parameters.length; index++) {
                result[index] = convert(values.get(index), parameters[index], 0, closeCallbackAfterInvocation);
            }
            return result;
        }
        int fixed = parameters.length - 1;
        if (values.size() < fixed) {
            throw new IllegalArgumentException("Paper varargs member requires more arguments");
        }
        Object[] result = new Object[parameters.length];
        for (int index = 0; index < fixed; index++) {
            result[index] = convert(values.get(index), parameters[index], 0, closeCallbackAfterInvocation);
        }
        Class<?> component = parameters[fixed].getComponentType();
        Object array = Array.newInstance(component, values.size() - fixed);
        for (int index = fixed; index < values.size(); index++) {
            Array.set(array, index - fixed,
                    convert(values.get(index), component, 0, closeCallbackAfterInvocation));
        }
        result[fixed] = array;
        return result;
    }

    private boolean canConvert(List<?> values, Class<?>[] parameters, boolean varargs) {
        if ((!varargs && values.size() != parameters.length) || (varargs && values.size() < parameters.length - 1)) {
            return false;
        }
        try {
            convertArguments(values, parameters, varargs, false);
            return true;
        } catch (IllegalArgumentException | IllegalStateException ignored) {
            return false;
        }
    }

    private Object convert(Object value, Class<?> expected, int depth, boolean closeCallbackAfterInvocation) {
        if (depth > MAXIMUM_DEPTH) {
            throw new IllegalArgumentException("Paper argument nesting is too deep");
        }
        if (value == null) {
            if (expected.isPrimitive()) {
                throw new IllegalArgumentException("Paper primitive argument cannot be null");
            }
            return null;
        }
        if (value instanceof Map<?, ?> map && map.containsKey(HANDLE)) {
            Object result = handle(value).value();
            if (!boxed(expected).isInstance(result)) {
                throw new IllegalArgumentException("Paper handle is not a " + expected.getName());
            }
            return result;
        }
        if (value instanceof Map<?, ?> map && map.get("$paperLong") instanceof String number) {
            try {
                return number(Long.valueOf(number), expected);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Paper long argument is outside the signed 64-bit range", exception);
            }
        }
        if (value instanceof ScriptCallback callback) {
            return callback(expected, callback, closeCallbackAfterInvocation);
        }
        if (value instanceof Map<?, ?> map && "plugin".equals(map.get("$paper"))) {
            if (!expected.isAssignableFrom(plugin.getClass()) && !expected.equals(Plugin.class)) {
                throw new IllegalArgumentException("Paper plugin token is not accepted by " + expected.getName());
            }
            return plugin;
        }
        if (value instanceof Map<?, ?> map && map.get("$paperType") instanceof String type) {
            Class<?> resolved = registry.requireType(type);
            if (!expected.equals(Class.class)) {
                throw new IllegalArgumentException("Paper type token requires java.lang.Class");
            }
            return resolved;
        }
        if (expected.isEnum()) {
            String name = value instanceof String text ? text
                    : value instanceof Map<?, ?> map && map.get("name") instanceof String text ? text : null;
            if (name == null) {
                throw new IllegalArgumentException("Paper enum argument requires a constant name");
            }
            @SuppressWarnings({"rawtypes", "unchecked"})
            Object result = Enum.valueOf((Class<? extends Enum>) expected.asSubclass(Enum.class), name);
            return result;
        }
        if (expected.equals(UUID.class) && value instanceof String text) {
            return UUID.fromString(text);
        }
        if (expected.equals(String.class) && value instanceof String
                || expected.equals(boolean.class) && value instanceof Boolean
                || expected.equals(Boolean.class) && value instanceof Boolean) {
            return value;
        }
        if ((expected.equals(char.class) || expected.equals(Character.class))
                && value instanceof String text && text.length() == 1) {
            return text.charAt(0);
        }
        if (value instanceof Number number && Number.class.isAssignableFrom(boxed(expected))) {
            return number(number, expected);
        }
        if (expected.isArray() && value instanceof List<?> list) {
            if (list.size() > MAXIMUM_COLLECTION) {
                throw new IllegalArgumentException("Paper array argument is too large");
            }
            Object result = Array.newInstance(expected.getComponentType(), list.size());
            for (int index = 0; index < list.size(); index++) {
                Array.set(result, index, convert(list.get(index), expected.getComponentType(), depth + 1,
                        closeCallbackAfterInvocation));
            }
            return result;
        }
        if (Collection.class.isAssignableFrom(expected) && value instanceof List<?> list) {
            if (list.size() > MAXIMUM_COLLECTION) {
                throw new IllegalArgumentException("Paper collection argument is too large");
            }
            Collection<Object> result = Set.class.isAssignableFrom(expected)
                    ? new LinkedHashSet<>() : new ArrayList<>();
            list.forEach(item -> result.add(convertUntyped(item, depth + 1)));
            return result;
        }
        if (Map.class.isAssignableFrom(expected) && value instanceof Map<?, ?> map) {
            Object entries = map.get("$paperMap");
            if (entries instanceof List<?> pairs) {
                Map<Object, Object> result = new LinkedHashMap<>();
                for (Object pair : pairs) {
                    if (!(pair instanceof List<?> values) || values.size() != 2) {
                        throw new IllegalArgumentException("Paper map entry must contain a key and value");
                    }
                    result.put(convertUntyped(values.get(0), depth + 1),
                            convertUntyped(values.get(1), depth + 1));
                }
                return result;
            }
            if (map.size() > MAXIMUM_COLLECTION) {
                throw new IllegalArgumentException("Paper map argument is too large");
            }
            Map<Object, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(key, convertUntyped(item, depth + 1)));
            return result;
        }
        if (expected.equals(Object.class)) {
            return convertUntyped(value, depth + 1);
        }
        if (boxed(expected).isInstance(value)) {
            return value;
        }
        throw new IllegalArgumentException("Paper argument cannot be converted to " + expected.getName());
    }

    private Object convertUntyped(Object value, int depth) {
        if (value instanceof Map<?, ?> map && map.containsKey(HANDLE)) {
            return handle(value).value();
        }
        if (value instanceof Map<?, ?> map && map.get("$paperLong") instanceof String number) {
            try {
                return Long.valueOf(number);
            } catch (NumberFormatException exception) {
                throw new IllegalArgumentException("Paper long argument is outside the signed 64-bit range", exception);
            }
        }
        if (value instanceof List<?> list) {
            return list.stream().map(item -> convertUntyped(item, depth + 1)).toList();
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> result = new LinkedHashMap<>();
            map.forEach((key, item) -> result.put(String.valueOf(key), convertUntyped(item, depth + 1)));
            return result;
        }
        return value;
    }

    private Object callback(Class<?> expected, ScriptCallback callback, boolean closeAfterInvocation) {
        if (!expected.isInterface()) {
            throw new IllegalArgumentException("Paper callback requires a functional interface");
        }
        List<Method> abstractMethods = java.util.Arrays.stream(expected.getMethods())
                .filter(method -> Modifier.isAbstract(method.getModifiers()))
                .filter(method -> !method.getDeclaringClass().equals(Object.class)).toList();
        if (abstractMethods.size() != 1) {
            throw new IllegalArgumentException("Paper callback type is not a functional interface: "
                    + expected.getName());
        }
        Method functional = abstractMethods.getFirst();
        return Proxy.newProxyInstance(expected.getClassLoader(), new Class<?>[] {expected},
                (proxy, method, arguments) -> {
            if (method.getDeclaringClass().equals(Object.class)) {
                return switch (method.getName()) {
                    case "toString" -> "ShamooPaperCallback[" + expected.getName() + "]";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == arguments[0];
                    default -> throw new IllegalStateException("unknown Object callback method");
                };
            }
            if (!method.equals(functional)) {
                throw new IllegalStateException("unexpected functional interface method: " + method);
            }
            return invokeCallback(callback, method.getReturnType(), arguments == null ? new Object[0] : arguments,
                    closeAfterInvocation);
                });
    }

    private Object invokeCallback(ScriptCallback callback, Class<?> returnType, Object[] arguments,
            boolean closeAfterInvocation) throws Exception {
        for (Object argument : arguments) {
            if (argument instanceof BukkitTask
                    || argument instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask) {
                retainNativeResource(argument);
            }
        }
        if (CompletionStage.class.isAssignableFrom(returnType)) {
            List<Object> encoded = new ArrayList<>(arguments.length);
            for (Object argument : arguments) {
                encoded.add(encode(argument, null, affinity(argument, null), new EncodeState(), 0));
            }
            CompletionStage<Object> result = callback.invoke(List.of(Map.of(
                    "$paperCallback", true, "arguments", encoded)));
            return closeAfterInvocation ? result.whenComplete((ignored, failure) -> callback.close()) : result;
        }
        PaperInvocationFrame frame = createFrame();
        try {
            List<Object> encoded = new ArrayList<>(arguments.length);
            for (Object argument : arguments) {
                encoded.add(encode(argument, frame, affinity(argument, null), new EncodeState(), 0));
            }
            Object result = frame.await(callback.invoke(List.of(Map.of(
                    "$paperCallback", true, FRAME, frame.id(), "arguments", encoded))));
            return returnType.equals(void.class) ? null : convert(result, returnType, 0, false);
        } finally {
            expire(frame);
            if (closeAfterInvocation) {
                callback.close();
            }
        }
    }

    private Object registerSchedulerResult(GeneratedPaperApiRegistry.Member member, Object result,
            boolean hasCallbacks) {
        if (!schedulerMember(member)) {
            return result;
        }
        if (result instanceof BukkitTask
                || result instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask
                || result instanceof Future<?>) {
            retainNativeResource(result);
        } else if (result instanceof Integer taskId && containsSchedulerCallback(member)) {
            retainNativeResource(new LegacyTask(taskId));
        }
        return result == null && hasCallbacks && member.descriptor().endsWith(")V")
                ? Boolean.TRUE : result;
    }

    private void retainNativeResource(Object resource) {
        synchronized (lifecycleLock) {
            if (closed.get()) {
                cancelResource(resource);
                throw new IllegalStateException("Paper API bridge is closed");
            }
            nativeResources.add(resource);
        }
    }

    private static boolean schedulerMember(GeneratedPaperApiRegistry.Member member) {
        return member.owner().startsWith("io.papermc.paper.threadedregions.scheduler.")
                || member.owner().startsWith("org.bukkit.scheduler.");
    }

    private static boolean containsSchedulerCallback(GeneratedPaperApiRegistry.Member member) {
        return member.descriptor().contains("Ljava/lang/Runnable;")
                || member.descriptor().contains("Ljava/util/function/Consumer;")
                || member.descriptor().contains("Ljava/util/concurrent/Callable;");
    }

    private static boolean oneShotSchedulerCallback(GeneratedPaperApiRegistry.Member member) {
        if (!schedulerMember(member) || !containsSchedulerCallback(member)) {
            return false;
        }
        String name = member.name();
        return !name.contains("Timer") && !name.contains("AtFixedRate")
                && (name.startsWith("run") || "execute".equals(name)
                        || "scheduleSyncDelayedTask".equals(name) || "scheduleAsyncDelayedTask".equals(name)
                        || "callSyncMethod".equals(name));
    }

    private static boolean containsCallback(Object value) {
        if (value instanceof ScriptCallback) {
            return true;
        }
        if (value instanceof Collection<?> collection) {
            return collection.stream().anyMatch(PaperJavaBridge::containsCallback);
        }
        if (value instanceof Map<?, ?> map) {
            return map.values().stream().anyMatch(PaperJavaBridge::containsCallback);
        }
        return false;
    }

    private Object encode(Object value, PaperInvocationFrame frame, Object inheritedAffinity,
            EncodeState state, int depth) {
        if (++state.nodes > 100_000 || depth > MAXIMUM_DEPTH) {
            throw new IllegalArgumentException("Paper return value exceeds the data boundary");
        }
        if (value == null || value instanceof String || value instanceof Boolean
                || value instanceof Byte || value instanceof Short || value instanceof Integer
                || value instanceof Float || value instanceof Double) {
            return value;
        }
        if (value instanceof Character character) {
            return character.toString();
        }
        if (value instanceof Long number) {
            return Map.of("$paperLong", number.toString());
        }
        if (value instanceof UUID uuid) {
            return uuid.toString();
        }
        if (value instanceof Enum<?> enumeration) {
            return Map.of("$paperEnum", enumeration.getDeclaringClass().getName(), "name", enumeration.name());
        }
        if (value instanceof Class<?> type) {
            return Map.of("$paperType", type.getName());
        }
        if (value instanceof Optional<?> optional) {
            return optional.isEmpty() ? null
                    : encode(optional.get(), frame, inheritedAffinity, state, depth + 1);
        }
        if (state.ancestors.put(value, Boolean.TRUE) != null) {
            throw new IllegalArgumentException("Paper return value contains a cycle");
        }
        try {
            if (value.getClass().isArray()) {
                int length = Array.getLength(value);
                requireCollectionSize(length);
                List<Object> result = new ArrayList<>(length);
                for (int index = 0; index < length; index++) {
                    Object item = Array.get(value, index);
                    result.add(encode(item, frame, affinity(item, inheritedAffinity), state, depth + 1));
                }
                return Collections.unmodifiableList(result);
            }
            if (value instanceof Collection<?> collection) {
                requireCollectionSize(collection.size());
                List<Object> result = new ArrayList<>(collection.size());
                for (Object item : collection) {
                    result.add(encode(item, frame, affinity(item, inheritedAffinity), state, depth + 1));
                }
                return Collections.unmodifiableList(result);
            }
            if (value instanceof Map<?, ?> map) {
                requireCollectionSize(map.size());
                List<Object> entries = new ArrayList<>(map.size());
                map.forEach((key, item) -> entries.add(List.of(
                        encode(key, frame, affinity(key, inheritedAffinity), state, depth + 1),
                        encode(item, frame, affinity(item, inheritedAffinity), state, depth + 1))));
                return Map.of("$paperMap", entries);
            }
            return encodeHandle(value, registry.exposedType(value.getClass()), frame,
                    affinity(value, inheritedAffinity));
        } finally {
            state.ancestors.remove(value);
        }
    }

    private Object encodeHandle(Object value, String type, PaperInvocationFrame frame, Object affinity) {
        String id;
        String identity;
        synchronized (lifecycleLock) {
            requireOpen();
            if (handles.size() >= maximumHandles) {
                throw new IllegalStateException("Paper handle limit is exhausted");
            }
            id = UUID.randomUUID().toString();
            identity = identities.computeIfAbsent(value, ignored -> UUID.randomUUID().toString());
            handles.put(id, new Handle(value, type, frame, affinity, identity));
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(HANDLE, id);
        result.put(OBJECT, identity);
        result.put("type", type);
        if (frame != null) {
            result.put(FRAME, frame.id());
        }
        return Collections.unmodifiableMap(result);
    }

    private PaperInvocationFrame createFrame() {
        synchronized (lifecycleLock) {
            requireOpen();
            String id = UUID.randomUUID().toString();
            PaperInvocationFrame frame = new PaperInvocationFrame(id, synchronousTimeout, maximumPendingFrameCalls);
            frames.put(id, frame);
            return frame;
        }
    }

    private void expire(PaperInvocationFrame frame) {
        frames.remove(frame.id(), frame);
        frame.close();
        synchronized (lifecycleLock) {
            List<Handle> removed = new ArrayList<>();
            handles.entrySet().removeIf(entry -> {
                if (entry.getValue().frame() != frame) {
                    return false;
                }
                removed.add(entry.getValue());
                return true;
            });
            removed.forEach(this::forgetIdentity);
        }
    }

    private void forgetIdentity(Handle removed) {
        boolean retained = handles.values().stream().anyMatch(handle -> handle.value() == removed.value());
        if (!retained) {
            identities.remove(removed.value(), removed.identity());
        }
    }

    private void discardNativeResource(Object result) {
        Object removed = null;
        synchronized (lifecycleLock) {
            if (nativeResources.remove(result)) {
                removed = result;
            } else if (result instanceof Integer taskId) {
                for (Object resource : nativeResources) {
                    if (resource instanceof LegacyTask legacy && legacy.id() == taskId) {
                        removed = resource;
                        break;
                    }
                }
                nativeResources.remove(removed);
            }
        }
        if (removed != null) {
            cancelResource(removed);
        }
    }

    private static Object affinity(Object value, Object inherited) {
        if (value instanceof PlayerEvent event) {
            return event.getPlayer();
        }
        if (value instanceof EntityEvent event) {
            return event.getEntity();
        }
        if (value instanceof InventoryEvent event) {
            return event.getView().getPlayer();
        }
        if (value instanceof BlockEvent event) {
            return event.getBlock().getLocation();
        }
        if (value instanceof Entity entity) {
            return entity;
        }
        if (value instanceof Location location) {
            return location.clone();
        }
        return inherited;
    }

    private static Object argumentAffinity(Object[] values) {
        for (Object value : values) {
            Object result = affinity(value, null);
            if (result != null) {
                return result;
            }
            if (value instanceof Object[] nested) {
                result = argumentAffinity(nested);
                if (result != null) {
                    return result;
                }
            }
        }
        return null;
    }

    private static CompletionStage<Object> flatten(Object value) {
        if (value instanceof CompletionStage<?> stage) {
            return stage.thenApply(item -> (Object) item);
        }
        return CompletableFuture.completedFuture(value);
    }

    private static Number number(Number value, Class<?> expected) {
        if (expected.equals(byte.class) || expected.equals(Byte.class)) {
            return (byte) exactInteger(value, Byte.MIN_VALUE, Byte.MAX_VALUE);
        }
        if (expected.equals(short.class) || expected.equals(Short.class)) {
            return (short) exactInteger(value, Short.MIN_VALUE, Short.MAX_VALUE);
        }
        if (expected.equals(int.class) || expected.equals(Integer.class)) {
            return (int) exactInteger(value, Integer.MIN_VALUE, Integer.MAX_VALUE);
        }
        if (expected.equals(long.class) || expected.equals(Long.class)) {
            return exactInteger(value, Long.MIN_VALUE, Long.MAX_VALUE);
        }
        if (expected.equals(float.class) || expected.equals(Float.class)) {
            return value.floatValue();
        }
        if (expected.equals(double.class) || expected.equals(Double.class)) {
            return value.doubleValue();
        }
        throw new IllegalArgumentException("unsupported Paper numeric type: " + expected.getName());
    }

    private static long exactInteger(Number value, long minimum, long maximum) {
        if (value instanceof Double || value instanceof Float) {
            double number = value.doubleValue();
            if (!Double.isFinite(number) || number != Math.rint(number) || number < minimum || number > maximum) {
                throw new IllegalArgumentException("Paper integer argument is fractional or outside its range");
            }
            return (long) number;
        }
        long number = value.longValue();
        if (number < minimum || number > maximum) {
            throw new IllegalArgumentException("Paper integer argument is outside its range");
        }
        return number;
    }

    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) {
            return type;
        }
        if (type.equals(boolean.class)) {
            return Boolean.class;
        }
        if (type.equals(byte.class)) {
            return Byte.class;
        }
        if (type.equals(short.class)) {
            return Short.class;
        }
        if (type.equals(int.class)) {
            return Integer.class;
        }
        if (type.equals(long.class)) {
            return Long.class;
        }
        if (type.equals(float.class)) {
            return Float.class;
        }
        if (type.equals(double.class)) {
            return Double.class;
        }
        if (type.equals(char.class)) {
            return Character.class;
        }
        return Void.class;
    }

    private static List<?> values(Map<?, ?> request, String name) {
        Object value = request.get(name);
        if (!(value instanceof List<?> list)) {
            throw new IllegalArgumentException("paperJava request " + name + " must be an array");
        }
        return list;
    }

    private static String text(Map<?, ?> request, String name) {
        Object value = request.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("paperJava request " + name + " must be nonblank text");
        }
        return text;
    }

    private static String optionalText(Map<?, ?> request, String name) {
        Object value = request.get(name);
        if (value == null) {
            return null;
        }
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("paperJava request " + name + " must be nonblank text");
        }
        return text;
    }

    private static void requireCollectionSize(int size) {
        if (size > MAXIMUM_COLLECTION) {
            throw new IllegalArgumentException("Paper return collection is too large");
        }
    }

    private void requireOpen() {
        if (closed.get()) {
            throw new IllegalStateException("Paper API bridge is closed");
        }
    }

    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        frames.values().forEach(PaperInvocationFrame::close);
        frames.clear();
        List<Object> resources;
        synchronized (lifecycleLock) {
            handles.clear();
            identities.clear();
            resources = List.copyOf(nativeResources);
            nativeResources.clear();
        }
        resources.forEach(this::cancelResource);
    }

    private void cancelResource(Object resource) {
        if (resource instanceof BukkitTask task) {
            task.cancel();
        } else if (resource instanceof io.papermc.paper.threadedregions.scheduler.ScheduledTask task) {
            task.cancel();
        } else if (resource instanceof LegacyTask task) {
            plugin.getServer().getScheduler().cancelTask(task.id());
        } else if (resource instanceof Future<?> future) {
            future.cancel(false);
        }
    }

    @FunctionalInterface
    private interface CheckedAction {
        Object run() throws Exception;
    }

    private record ResolvedMember(GeneratedPaperApiRegistry.Member member) {
    }

    private record Handle(Object value, String type, PaperInvocationFrame frame, Object affinity, String identity) {
    }

    private record LegacyTask(int id) {
    }

    private static final class EncodeState {
        private final IdentityHashMap<Object, Boolean> ancestors = new IdentityHashMap<>();
        private int nodes;
    }
}
