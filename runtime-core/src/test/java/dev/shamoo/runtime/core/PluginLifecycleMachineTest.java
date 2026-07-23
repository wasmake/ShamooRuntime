package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class PluginLifecycleMachineTest {
    @Test
    void acceptsEveryCentralLegalTransitionAndRejectsEveryOtherTransition() {
        for (PluginLifecycleState source : PluginLifecycleState.values()) {
            Set<PluginLifecycleState> legal = PluginLifecycleMachine.transitions().get(source);
            assertTrue(legal != null, source.name());
            for (PluginLifecycleState target : PluginLifecycleState.values()) {
                PluginLifecycleMachine machine = new PluginLifecycleMachine(
                        new PluginId("state-test"), Clock.systemUTC(), source);
                UUID correlation = UUID.randomUUID();
                if (legal.contains(target)) {
                    LifecycleTransition transition = machine.transition(target, correlation, "test");
                    assertEquals(target, machine.state());
                    assertEquals(correlation, transition.correlationId());
                    assertEquals(java.util.List.of(transition), machine.history());
                } else {
                    assertThrows(IllegalLifecycleTransitionError.class,
                            () -> machine.transition(target, correlation, "test"), source + " -> " + target);
                    assertEquals(source, machine.state());
                    assertTrue(machine.history().isEmpty());
                }
            }
        }
    }

    @Test
    void historyAndTransitionTableAreImmutable() {
        PluginLifecycleMachine machine = new PluginLifecycleMachine(new PluginId("immutable"));
        machine.transition(PluginLifecycleState.LOADING, UUID.randomUUID(), "load");
        assertThrows(UnsupportedOperationException.class, () -> machine.history().clear());
        assertThrows(UnsupportedOperationException.class,
                () -> PluginLifecycleMachine.transitions().clear());
    }
}
