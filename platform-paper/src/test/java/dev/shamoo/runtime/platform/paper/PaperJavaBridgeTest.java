package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
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
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CloseResource",
        "PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.UseProperClassLoader"})
class PaperJavaBridgeTest {
    private static final String FIXTURE = "dev.shamoo.runtime.platform.paper.PaperJavaBridgeTest$Fixture";

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
                     }]
                   }]
                 }
                """.formatted(FIXTURE, FIXTURE, FIXTURE, FIXTURE, FIXTURE));
        return GeneratedPaperApiRegistry.parse(getClass().getClassLoader(), model);
    }

    public static final class Fixture {
        public int length() {
            return 0;
        }

        public long echo(long value) {
            return value;
        }

        public boolean invokeBoolean(BooleanSupplier callback) {
            return callback.getAsBoolean();
        }
    }
}
