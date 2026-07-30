package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shamoo.runtime.core.InvocationRejectedError;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ScriptCallback;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidCatchingThrowable", "PMD.AvoidDuplicateLiterals", "PMD.CloseResource",
        "PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.UseProperClassLoader"})
class PaperJavaBridgeTest {
    private static final String FIXTURE = "dev.shamoo.runtime.platform.paper.PaperJavaBridgeTest$Fixture";
    private static final String FIXTURE_EVENT =
            "dev.shamoo.runtime.platform.paper.PaperJavaBridgeTest$FixtureEvent";
    private static final String GLOBAL_SCHEDULER =
            "io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler";
    private static final String GLOBAL_SCHEDULER_RUN =
            "(Lorg/bukkit/plugin/Plugin;Ljava/util/function/Consumer;)"
                    + "Lio/papermc/paper/threadedregions/scheduler/ScheduledTask;";

    @Test
    void constructsAndInvokesOnlyCataloguedMembersThroughTheScheduler() throws IOException {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(scheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ScheduledTask> action = invocation.getArgument(1, Consumer.class);
            action.accept(task);
            return task;
        });
        PaperJavaBridge bridge = new PaperJavaBridge(plugin, new PluginId("fixture"), UUID.randomUUID(),
                registry(), Duration.ofSeconds(1), 8, 32, () -> true);

        Map<?, ?> description = assertInstanceOf(Map.class,
                bridge.invoke(List.of(Map.of("operation", "describe"))));
        assertEquals(true, description.get("replacementPresent"));
        assertEquals(true, description.get("platformEnabled"));

        Object constructed = bridge.invoke(List.of(Map.of(
                "operation", "construct",
                "type", FIXTURE,
                "descriptor", "()V",
                "arguments", List.of())));
        Map<?, ?> handle = assertInstanceOf(Map.class,
                assertInstanceOf(CompletionStage.class, constructed).toCompletableFuture().join());
        Object invoked = bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", FIXTURE,
                "name", "length",
                "descriptor", "()I",
                "target", handle,
                "arguments", List.of())));

        assertEquals(0, assertInstanceOf(CompletionStage.class, invoked).toCompletableFuture().join());
        Object longValue = bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", FIXTURE,
                "name", "echo",
                "descriptor", "(J)J",
                "target", handle,
                "arguments", List.of(Map.of("$paperLong", Long.toString(Long.MIN_VALUE))))));
        assertEquals(Map.of("$paperLong", Long.toString(Long.MIN_VALUE)),
                assertInstanceOf(CompletionStage.class, longValue).toCompletableFuture().join());
        ScriptCallback rejected = arguments -> {
            throw new InvocationRejectedError(new PluginId("fixture"));
        };
        Object dropped = bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", FIXTURE,
                "name", "invokeBoolean",
                "descriptor", "(Ljava/util/function/BooleanSupplier;)Z",
                "target", handle,
                "arguments", List.of(rejected))));
        assertEquals(false, assertInstanceOf(CompletionStage.class, dropped).toCompletableFuture().join());
        IllegalStateException callbackFailure = new IllegalStateException("failed");
        ScriptCallback failing = arguments -> {
            throw callbackFailure;
        };
        Object failed = bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", FIXTURE,
                "name", "invokeBoolean",
                "descriptor", "(Ljava/util/function/BooleanSupplier;)Z",
                "target", handle,
                "arguments", List.of(failing))));
        CompletionException exposed = assertThrows(CompletionException.class,
                () -> assertInstanceOf(CompletionStage.class, failed).toCompletableFuture().join());
        assertSame(callbackFailure, exposed.getCause());
        assertThrows(IllegalArgumentException.class, () -> bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", FIXTURE,
                "name", "substring",
                "descriptor", "(I)Ljava/lang/String;",
                "target", handle,
                "arguments", List.of(0)))));
        bridge.close();
        assertThrows(IllegalStateException.class, () -> bridge.invoke(List.of(Map.of("operation", "describe"))));
    }

    @Test
    void keepsEventDerivedHandlesInTheOriginatingFrame() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        when(plugin.getServer()).thenReturn(server);
        Thread originThread = Thread.currentThread();
        when(server.isGlobalTickThread()).thenAnswer(ignored -> Thread.currentThread().equals(originThread));
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(scheduler.run(eq(plugin), any())).thenReturn(task);
        PaperJavaBridge bridge = new PaperJavaBridge(plugin, new PluginId("fixture"), UUID.randomUUID(),
                registry(), Duration.ofSeconds(1), 8, 32);
        AtomicReference<Thread> scriptThread = new AtomicReference<>();
        AtomicReference<Map<?, ?>> returnedFixture = new AtomicReference<>();

        bridge.dispatchEvent(new FixtureEvent(), arguments -> {
            CompletableFuture<Object> result = new CompletableFuture<>();
            scriptThread.set(new Thread(() -> {
                try {
                    Map<?, ?> event = assertInstanceOf(Map.class, arguments.getFirst());
                    Map<?, ?> fixture = stage(bridge.invoke(List.of(Map.of(
                            "operation", "invoke",
                            "type", FIXTURE_EVENT,
                            "name", "fixture",
                            "descriptor", "()L" + FIXTURE.replace('.', '/') + ";",
                            "target", event,
                            "arguments", List.of()))));
                    returnedFixture.set(fixture);
                    result.complete(stage(bridge.invoke(List.of(Map.of(
                            "operation", "invoke",
                            "type", FIXTURE,
                            "name", "length",
                            "descriptor", "()I",
                            "target", fixture,
                            "arguments", List.of())))));
                } catch (Throwable failure) {
                    result.completeExceptionally(failure);
                }
            }));
            scriptThread.get().start();
            return result;
        });

        scriptThread.get().join();
        verifyNoInteractions(scheduler);
        int detachedLength = stage(bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", FIXTURE,
                "name", "length",
                "descriptor", "()I",
                "target", returnedFixture.get(),
                "arguments", List.of()))));
        assertEquals(0, detachedLength);
        assertEquals(true, bridge.invoke(List.of(Map.of(
                "operation", "release",
                "handle", returnedFixture.get().get("$paperHandle")))));
        Map<?, ?> description = assertInstanceOf(Map.class,
                bridge.invoke(List.of(Map.of("operation", "describe"))));
        assertEquals(0, description.get("handles"));
        bridge.close();
    }

    @Test
    void forgetsCompletedOneShotSchedulerResources() throws IOException {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        AtomicReference<Consumer<ScheduledTask>> execution = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.isEnabled()).thenReturn(true);
        when(server.isGlobalTickThread()).thenReturn(true);
        when(scheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Consumer<ScheduledTask> action = invocation.getArgument(1, Consumer.class);
            execution.set(action);
            return task;
        });
        Fixture.globalSchedulerFixture = scheduler;
        PaperJavaBridge bridge = new PaperJavaBridge(plugin, new PluginId("fixture"), UUID.randomUUID(),
                registry(), Duration.ofSeconds(1), 8, 32);
        Map<?, ?> fixture = stage(bridge.invoke(List.of(Map.of(
                "operation", "construct",
                "type", FIXTURE,
                "descriptor", "()V",
                "arguments", List.of()))));
        Map<?, ?> schedulerHandle = stage(bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", FIXTURE,
                "name", "scheduler",
                "descriptor", "()Lio/papermc/paper/threadedregions/scheduler/GlobalRegionScheduler;",
                "target", fixture,
                "arguments", List.of()))));
        CountingCallback callback = new CountingCallback();

        Map<?, ?> taskHandle = stage(bridge.invoke(List.of(Map.of(
                "operation", "invoke",
                "type", GLOBAL_SCHEDULER,
                "name", "run",
                "descriptor", GLOBAL_SCHEDULER_RUN,
                "target", schedulerHandle,
                "arguments", List.of(Map.of("$paper", "plugin"), callback)))));

        assertEquals(true, bridge.invoke(List.of(Map.of(
                "operation", "release",
                "handle", taskHandle.get("$paperHandle")))));
        execution.get().accept(task);
        assertEquals(1, callback.closed.get());
        bridge.close();
        verify(task, never()).cancel();
    }

    @Test
    void rejectsHandlesReturnedAfterTheirFrameExpires() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        PaperJavaBridge bridge = new PaperJavaBridge(plugin, new PluginId("fixture"), UUID.randomUUID(),
                registry(), Duration.ofSeconds(1), 8, 32);
        FixtureEvent event = new FixtureEvent();
        AtomicReference<CompletionStage<Object>> pending = new AtomicReference<>();

        bridge.dispatchEvent(event, arguments -> {
            pending.set(stageValue(bridge.invoke(List.of(Map.of(
                    "operation", "invoke",
                    "type", FIXTURE_EVENT,
                    "name", "delayedFixture",
                    "descriptor", "()Ljava/util/concurrent/CompletionStage;",
                    "target", arguments.getFirst(),
                    "arguments", List.of())))));
            return CompletableFuture.completedFuture(null);
        });
        event.completeDelayedFixture();

        CompletionException failure = assertThrows(CompletionException.class,
                () -> pending.get().toCompletableFuture().join());
        assertInstanceOf(IllegalStateException.class, failure.getCause());
        Map<?, ?> description = assertInstanceOf(Map.class,
                bridge.invoke(List.of(Map.of("operation", "describe"))));
        assertEquals(0, description.get("handles"));
        bridge.close();
    }

    private static <T> T stage(Object value) {
        @SuppressWarnings("unchecked")
        T result = (T) stageValue(value).toCompletableFuture().join();
        return result;
    }

    @SuppressWarnings("unchecked")
    private static CompletionStage<Object> stageValue(Object value) {
        return (CompletionStage<Object>) assertInstanceOf(CompletionStage.class, value);
    }

    private GeneratedPaperApiRegistry registry() throws IOException {
        var model = new ObjectMapper().readTree("""
                {
                  "declarations": [{
                    "javaName": "%s",
                    "constructors": [{"id": "%s#<init>()V", "descriptor": "()V"}],
                    "methods": [{
                       "id": "%s#length()I",
                       "name": "length",
                       "descriptor": "()I"
                     }, {
                       "id": "%s#echo(J)J",
                       "name": "echo",
                       "descriptor": "(J)J"
                      }, {
                        "id": "%s#invokeBoolean(Ljava/util/function/BooleanSupplier;)Z",
                        "name": "invokeBoolean",
                        "descriptor": "(Ljava/util/function/BooleanSupplier;)Z"
                      }, {
                        "id": "%s#scheduler()L%s;",
                        "name": "scheduler",
                        "descriptor": "()L%s;"
                       }]
                    }, {
                      "javaName": "%s",
                      "methods": [{
                        "id": "%s#fixture()L%s;",
                        "name": "fixture",
                        "descriptor": "()L%s;"
                      }, {
                        "id": "%s#delayedFixture()Ljava/util/concurrent/CompletionStage;",
                        "name": "delayedFixture",
                        "descriptor": "()Ljava/util/concurrent/CompletionStage;"
                      }]
                    }, {
                      "javaName": "%s",
                      "methods": [{
                        "id": "%s#run%s",
                        "name": "run",
                        "descriptor": "%s"
                      }]
                    }]
                  }
                """.formatted(FIXTURE, FIXTURE, FIXTURE, FIXTURE, FIXTURE, FIXTURE,
                        GLOBAL_SCHEDULER.replace('.', '/'), GLOBAL_SCHEDULER.replace('.', '/'),
                        FIXTURE_EVENT, FIXTURE_EVENT, FIXTURE.replace('.', '/'), FIXTURE.replace('.', '/'),
                        FIXTURE_EVENT, GLOBAL_SCHEDULER, GLOBAL_SCHEDULER, GLOBAL_SCHEDULER_RUN,
                        GLOBAL_SCHEDULER_RUN));
        return GeneratedPaperApiRegistry.parse(getClass().getClassLoader(), model);
    }

    public static final class Fixture {
        private static GlobalRegionScheduler globalSchedulerFixture;

        public int length() {
            return 0;
        }

        public long echo(long value) {
            return value;
        }

        public boolean invokeBoolean(BooleanSupplier callback) {
            return callback.getAsBoolean();
        }

        public GlobalRegionScheduler scheduler() {
            return globalSchedulerFixture;
        }
    }

    private static final class CountingCallback implements ScriptCallback {
        private final AtomicInteger closed = new AtomicInteger();

        @Override
        public CompletionStage<Object> invoke(List<Object> arguments) {
            return CompletableFuture.completedFuture(null);
        }

        @Override
        public void close() {
            closed.incrementAndGet();
        }
    }

    public static final class FixtureEvent extends Event {
        private static final HandlerList HANDLERS = new HandlerList();
        private final CompletableFuture<Fixture> delayedResult = new CompletableFuture<>();

        public Fixture fixture() {
            return new Fixture();
        }

        public CompletionStage<Fixture> delayedFixture() {
            return delayedResult.thenApply(value -> value);
        }

        public void completeDelayedFixture() {
            delayedResult.complete(new Fixture());
        }

        @Override
        public HandlerList getHandlers() {
            return HANDLERS;
        }
    }
}
