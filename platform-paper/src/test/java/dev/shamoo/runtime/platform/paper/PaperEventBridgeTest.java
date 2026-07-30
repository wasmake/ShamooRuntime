package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.shamoo.runtime.core.InvocationRejectedError;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ResourceRegistry;
import java.util.concurrent.atomic.AtomicReference;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.EventPriority;
import org.bukkit.plugin.EventExecutor;
import org.bukkit.plugin.PluginManager;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

class PaperEventBridgeTest {
    private static final PluginId OWNER = new PluginId("fixture");

    @Test
    void dropsEventsWhilePluginInvocationAdmissionIsClosed() {
        try (Fixture fixture = fixture(event -> {
            throw new InvocationRejectedError(OWNER);
        })) {
            assertDoesNotThrow(() -> fixture.executor().execute(null, mock(Event.class)),
                    "events must be dropped while invocation admission is closed");
        }
    }

    @Test
    void exposesOtherEventDispatcherFailures() {
        IllegalStateException failure = new IllegalStateException("failed");
        try (Fixture fixture = fixture(event -> {
            throw failure;
        })) {
            assertThrows(EventException.class,
                    () -> fixture.executor().execute(null, mock(Event.class)),
                    "dispatcher failures must remain visible to Paper");
        }
    }

    private Fixture fixture(PaperEventBridge.SynchronousEventDispatcher dispatcher) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        PluginManager pluginManager = mock(PluginManager.class);
        AtomicReference<EventExecutor> executor = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(server.getPluginManager()).thenReturn(pluginManager);
        doAnswer(invocation -> {
            executor.set(invocation.getArgument(3, EventExecutor.class));
            return null;
        }).when(pluginManager).registerEvent(eq(Event.class), any(), eq(EventPriority.NORMAL), any(), eq(plugin),
                eq(true));
        PaperEventBridge bridge = new PaperEventBridge(plugin, new ResourceRegistry());

        PaperEventBridge.Subscription subscription = bridge.subscribe(
                OWNER, Event.class, EventPriority.NORMAL, false, dispatcher);

        return new Fixture(executor.get(), subscription);
    }

    private record Fixture(EventExecutor executor, PaperEventBridge.Subscription subscription)
            implements AutoCloseable {
        @Override
        public void close() {
            subscription.close();
        }
    }
}
