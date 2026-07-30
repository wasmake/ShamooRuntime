package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.shamoo.runtime.core.CompiledBindingMetadata;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.ScriptCallback;
import dev.shamoo.runtime.protocol.ProtocolVersion;
import io.papermc.paper.threadedregions.scheduler.GlobalRegionScheduler;
import io.papermc.paper.threadedregions.scheduler.ScheduledTask;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.CloseResource",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.UnitTestContainsTooManyAsserts"
})
class PaperCommandBridgeTest {
    @Test
    void lifecycleApiFallsBackWhenItCannotRemoveImmediately() {
        assertEquals(PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK,
                PaperCommandBridge.selectCapability(true));
        assertEquals(PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK,
                PaperCommandBridge.selectCapability(false));
    }

    @Test
    void aggregatesRoutesSelectsMostSpecificAndClosesOnlyOwnedRoute() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        CommandMap map = mock(CommandMap.class);
        Map<String, Command> known = new HashMap<>();
        AtomicReference<Command> command = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("ShamooRuntime");
        when(server.getCommandMap()).thenReturn(map);
        when(map.getKnownCommands()).thenReturn(known);
        when(map.register(anyString(), any(Command.class))).thenAnswer(invocation -> {
            Command registered = invocation.getArgument(1);
            command.set(registered);
            known.put("demo", registered);
            return true;
        });
        PaperCommandContextBridge contexts = new PaperCommandContextBridge(server);
        PaperCommandBridge bridge = new PaperCommandBridge(plugin, contexts,
                PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK);
        AtomicInteger generic = new AtomicInteger();
        AtomicInteger literal = new AtomicInteger();
        PluginId owner = new PluginId("fixture");

        PaperCommandBridge.RouteRegistration genericRegistration = bridge.register(owner, binding("generic"),
                "demo", List.of("d"), route("<value>", List.of(argument("value"))),
                values -> {
                    generic.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }).toCompletableFuture().join();
        PaperCommandBridge.RouteRegistration literalRegistration = bridge.register(owner, binding("literal"),
                "demo", List.of("d"), route("alpha", List.of()), values -> {
                    literal.incrementAndGet();
                    return CompletableFuture.completedFuture(null);
                }).toCompletableFuture().join();

        assertEquals("test", command.get().getDescription());

        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("Sender");
        command.get().execute(sender, "demo", new String[] {"alpha"});
        assertEquals(0, generic.get());
        assertEquals(1, literal.get());
        assertEquals(1, bridge.aggregateCount());

        literalRegistration.close();
        command.get().execute(sender, "demo", new String[] {"alpha"});
        assertEquals(1, generic.get());
        assertEquals(1, bridge.aggregateCount());

        genericRegistration.close();
        assertEquals(0, bridge.aggregateCount());
        assertFalse(known.containsValue(command.get()));
        verify(map).register(anyString(), any(Command.class));
    }

    @Test
    void sharedRootRequiresExactAliasAgreement() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        CommandMap map = mock(CommandMap.class);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("runtime");
        when(server.getCommandMap()).thenReturn(map);
        when(map.getKnownCommands()).thenReturn(new HashMap<>());
        when(map.register(anyString(), any(Command.class))).thenReturn(true);
        PaperCommandBridge bridge = new PaperCommandBridge(plugin,
                new PaperCommandContextBridge(server), PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK);
        PluginId owner = new PluginId("fixture");
        PaperCommandBridge.RouteRegistration registration = bridge.register(owner, binding("first"),
                "demo", List.of("d"), route("first", List.of()),
                values -> CompletableFuture.completedFuture(null)).toCompletableFuture().join();

        assertThrows(CompletionException.class, () -> bridge.register(owner, binding("second"),
                "demo", List.of("other"), route("second", List.of()),
                values -> CompletableFuture.completedFuture(null)).toCompletableFuture().join());
        registration.close();
    }

    @Test
    void closeRemovesEveryKnownCommandLabel() {
        Command command = new Command("primary") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] arguments) {
                return true;
            }
        };
        Map<String, Command> known = new HashMap<>();
        known.put("primary", command);
        known.put("plugin:primary", command);

        PaperCommandBridge.removeKnownCommands(known, command);

        assertFalse(known.containsValue(command));
    }

    @Test
    void closeRemovesCommandsWhenPaperEntryIteratorDoesNotSupportRemoval() {
        Map<String, Command> known = new HashMap<>() {
            @Override
            public Set<Entry<String, Command>> entrySet() {
                return Collections.unmodifiableSet(super.entrySet());
            }
        };
        Command command = new Command("primary") {
            @Override
            public boolean execute(CommandSender sender, String label, String[] arguments) {
                return true;
            }
        };
        known.put("primary", command);
        known.put("plugin:primary", command);

        PaperCommandBridge.removeKnownCommands(known, command);

        assertFalse(known.containsValue(command));
    }

    @Test
    void dispatchDoesNotWaitForNodeCompletionAndCloseReleasesCallback() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        CommandMap map = mock(CommandMap.class);
        AtomicReference<Command> command = new AtomicReference<>();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("runtime");
        when(server.getCommandMap()).thenReturn(map);
        when(map.getKnownCommands()).thenReturn(new HashMap<>());
        when(map.register(anyString(), any(Command.class))).thenAnswer(invocation -> {
            command.set(invocation.getArgument(1));
            return true;
        });
        PaperCommandBridge bridge = new PaperCommandBridge(plugin, new PaperCommandContextBridge(server),
                PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK);
        CompletableFuture<Object> pending = new CompletableFuture<>();
        AtomicBoolean callbackClosed = new AtomicBoolean();
        ScriptCallback callback = new ScriptCallback() {
            @Override
            public java.util.concurrent.CompletionStage<Object> invoke(List<Object> arguments) {
                return pending;
            }

            @Override
            public void close() {
                callbackClosed.set(true);
            }
        };
        PaperCommandBridge.RouteRegistration registration = bridge.register(new PluginId("fixture"),
                binding("async"), "async", List.of(), route("", List.of()), callback)
                .toCompletableFuture().join();
        CommandSender sender = mock(CommandSender.class);
        when(sender.getName()).thenReturn("Sender");

        CompletableFuture<Boolean> dispatched = CompletableFuture.supplyAsync(
                () -> command.get().execute(sender, "async", new String[0]));
        try {
            assertTrue(dispatched.get(1, TimeUnit.SECONDS));
            assertFalse(pending.isDone());
        } finally {
            pending.complete(null);
        }
        registration.close();
        assertTrue(callbackClosed.get());
    }

    @Test
    void registrationAndCommandMapCleanupRunOnGlobalScheduler() throws Exception {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        CommandMap map = mock(CommandMap.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        Map<String, Command> known = new HashMap<>();
        BlockingQueue<Consumer<ScheduledTask>> operations = new ArrayBlockingQueue<>(2);
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("runtime");
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("command-routing-test"));
        when(server.getCommandMap()).thenReturn(map);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(map.getKnownCommands()).thenReturn(known);
        when(map.register(anyString(), any(Command.class))).thenAnswer(invocation -> {
            known.put("routed", invocation.getArgument(1));
            return true;
        });
        when(scheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            operations.add(invocation.getArgument(1));
            return task;
        });
        PaperCommandContextBridge contexts = new PaperCommandContextBridge(
                plugin, () -> false, entity -> false);
        PaperCommandBridge bridge = new PaperCommandBridge(
                plugin, contexts, PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK);

        java.util.concurrent.CompletionStage<PaperCommandBridge.RouteRegistration> registrationStage =
                bridge.register(new PluginId("fixture"), binding("routed"), "routed", List.of(),
                        route("", List.of()), values -> CompletableFuture.completedFuture(null));
        verify(map, never()).register(anyString(), any(Command.class));
        operations.poll(1, TimeUnit.SECONDS).accept(task);
        PaperCommandBridge.RouteRegistration registration = registrationStage.toCompletableFuture().join();

        CompletableFuture<Void> close = CompletableFuture.runAsync(registration::close);
        Consumer<ScheduledTask> cleanup = operations.poll(1, TimeUnit.SECONDS);
        assertNotNull(cleanup);
        assertTrue(known.containsKey("routed"));
        cleanup.accept(task);
        close.get(1, TimeUnit.SECONDS);
        assertFalse(known.containsKey("routed"));
    }

    @Test
    void platformShutdownPreclosesNativeCommandsWithoutReschedulingRouteCleanup() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        CommandMap map = mock(CommandMap.class);
        GlobalRegionScheduler scheduler = mock(GlobalRegionScheduler.class);
        ScheduledTask task = mock(ScheduledTask.class);
        Map<String, Command> known = new HashMap<>();
        AtomicBoolean callbackClosed = new AtomicBoolean();
        when(plugin.getServer()).thenReturn(server);
        when(plugin.getName()).thenReturn("runtime");
        when(plugin.isEnabled()).thenReturn(false);
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getLogger("command-shutdown-test"));
        when(server.getCommandMap()).thenReturn(map);
        when(server.getGlobalRegionScheduler()).thenReturn(scheduler);
        when(map.getKnownCommands()).thenReturn(known);
        when(map.register(anyString(), any(Command.class))).thenAnswer(invocation -> {
            known.put("shutdown", invocation.getArgument(1));
            return true;
        });
        when(scheduler.run(eq(plugin), any())).thenAnswer(invocation -> {
            invocation.<Consumer<ScheduledTask>>getArgument(1).accept(task);
            return task;
        });
        PaperCommandBridge bridge = new PaperCommandBridge(plugin,
                new PaperCommandContextBridge(plugin, () -> true, entity -> true),
                PaperCommandBridge.Capability.COMMAND_MAP_FALLBACK);
        ScriptCallback callback = new ScriptCallback() {
            @Override
            public java.util.concurrent.CompletionStage<Object> invoke(List<Object> arguments) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public void close() {
                callbackClosed.set(true);
            }
        };
        PaperCommandBridge.RouteRegistration registration = bridge.register(new PluginId("fixture"),
                binding("shutdown"), "shutdown", List.of(), route("", List.of()), callback)
                .toCompletableFuture().join();

        bridge.close();
        clearInvocations(scheduler);
        registration.close();

        verifyNoInteractions(scheduler);
        assertFalse(known.containsKey("shutdown"));
        assertTrue(callbackClosed.get());
    }

    private static CompiledBindingMetadata binding(String method) {
        return new CompiledBindingMetadata("paper", "paperRegisterCommand", "fixture", method,
                ProtocolVersion.CURRENT);
    }

    private static Map<String, Object> route(String syntax, List<Object> arguments) {
        return Map.of("syntax", syntax, "description", "test", "permission", "", "sender", "any",
                "arguments", arguments, "options", List.of());
    }

    private static Map<String, Object> argument(String name) {
        return Map.of("name", name, "parser", "string", "suggestions", List.of());
    }
}
