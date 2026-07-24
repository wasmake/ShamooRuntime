package dev.shamoo.runtime.core;

import java.time.Clock;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Thread-safe state machine backed by the single authoritative transition table. */
public final class PluginLifecycleMachine {
    private static final Map<PluginLifecycleState, Set<PluginLifecycleState>> LEGAL = legalTransitions();
    private final PluginId pluginId;
    private final Clock clock;
    private final List<LifecycleTransition> transitionHistory = new java.util.ArrayList<>();
    private PluginLifecycleState currentState = PluginLifecycleState.DISCOVERED;

    public PluginLifecycleMachine(PluginId pluginId) {
        this(pluginId, Clock.systemUTC());
    }

    PluginLifecycleMachine(PluginId pluginId, Clock clock) {
        this(pluginId, clock, PluginLifecycleState.DISCOVERED);
    }

    PluginLifecycleMachine(PluginId pluginId, Clock clock, PluginLifecycleState initialState) {
        this.pluginId = Objects.requireNonNull(pluginId, "pluginId");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.currentState = Objects.requireNonNull(initialState, "initialState");
    }

    public synchronized PluginLifecycleState state() {
        return currentState;
    }

    public synchronized List<LifecycleTransition> history() {
        return List.copyOf(transitionHistory);
    }

    public synchronized LifecycleTransition transition(
            PluginLifecycleState target, UUID correlationId, String reason) {
        Objects.requireNonNull(target, "target");
        Objects.requireNonNull(correlationId, "correlationId");
        Objects.requireNonNull(reason, "reason");
        if (!LEGAL.getOrDefault(currentState, Set.of()).contains(target)) {
            throw new IllegalLifecycleTransitionError(pluginId, currentState, target, correlationId);
        }
        LifecycleTransition transition = new LifecycleTransition(
                currentState, target, clock.instant(), correlationId, reason);
        currentState = target;
        transitionHistory.add(transition);
        return transition;
    }

    public static Map<PluginLifecycleState, Set<PluginLifecycleState>> transitions() {
        return LEGAL.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> Set.copyOf(entry.getValue())));
    }

    private static Map<PluginLifecycleState, Set<PluginLifecycleState>> legalTransitions() {
        Map<PluginLifecycleState, Set<PluginLifecycleState>> transitions =
                new EnumMap<>(PluginLifecycleState.class);
        allow(transitions, PluginLifecycleState.DISCOVERED,
                PluginLifecycleState.BLOCKED, PluginLifecycleState.LOADING, PluginLifecycleState.UNLOADING,
                PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.BLOCKED,
                PluginLifecycleState.DISCOVERED, PluginLifecycleState.UNLOADING,
                PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.LOADING,
                PluginLifecycleState.LOADED, PluginLifecycleState.LOAD_FAILED);
        allow(transitions, PluginLifecycleState.LOAD_FAILED,
                PluginLifecycleState.LOADING, PluginLifecycleState.UNLOADING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.LOADED,
                PluginLifecycleState.ENABLING, PluginLifecycleState.UNLOADING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.ENABLING,
                PluginLifecycleState.ENABLED, PluginLifecycleState.ENABLE_FAILED);
        allow(transitions, PluginLifecycleState.ENABLE_FAILED,
                PluginLifecycleState.ENABLING, PluginLifecycleState.UNLOADING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.ENABLED,
                PluginLifecycleState.READYING, PluginLifecycleState.DRAINING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.READYING,
                PluginLifecycleState.READY, PluginLifecycleState.READY_FAILED);
        allow(transitions, PluginLifecycleState.READY_FAILED,
                PluginLifecycleState.READYING, PluginLifecycleState.DRAINING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.READY,
                PluginLifecycleState.DRAINING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.DRAINING,
                PluginLifecycleState.DISABLING, PluginLifecycleState.DRAIN_FAILED);
        allow(transitions, PluginLifecycleState.DRAIN_FAILED,
                PluginLifecycleState.DRAINING, PluginLifecycleState.DISABLING,
                PluginLifecycleState.UNLOADING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.DISABLING,
                PluginLifecycleState.DISABLED, PluginLifecycleState.DISABLE_FAILED);
        allow(transitions, PluginLifecycleState.DISABLE_FAILED,
                PluginLifecycleState.DISABLING, PluginLifecycleState.UNLOADING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.DISABLED,
                PluginLifecycleState.ENABLING, PluginLifecycleState.UNLOADING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.UNLOADING,
                PluginLifecycleState.UNLOADED, PluginLifecycleState.UNLOAD_FAILED);
        allow(transitions, PluginLifecycleState.UNLOAD_FAILED,
                PluginLifecycleState.UNLOADING, PluginLifecycleState.QUARANTINED);
        allow(transitions, PluginLifecycleState.QUARANTINED,
                PluginLifecycleState.UNLOADING);
        allow(transitions, PluginLifecycleState.UNLOADED);
        return Map.copyOf(transitions);
    }

    private static void allow(
            Map<PluginLifecycleState, Set<PluginLifecycleState>> transitions,
            PluginLifecycleState source,
            PluginLifecycleState... targets) {
        transitions.put(source, targets.length == 0
                ? Set.of() : Set.copyOf(EnumSet.of(targets[0], targets)));
    }
}
