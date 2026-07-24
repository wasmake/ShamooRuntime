package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.shamoo.runtime.protocol.EventContract;
import dev.shamoo.runtime.protocol.ProxyMessageCodec;
import dev.shamoo.runtime.protocol.ProxyMessageEnvelope;
import dev.shamoo.runtime.protocol.SemanticVersion;
import dev.shamoo.runtime.protocol.SemverRange;
import dev.shamoo.runtime.protocol.ServiceContract;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.CloseResource",
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.AvoidDuplicateLiterals"
})
class Phase10ContractsTest {
    @Test
    void stableProxyResolvesNewProviderGenerationAfterTransactionalReload() {
        PluginRuntimeContext[] consumerContext = new PluginRuntimeContext[1];
        AtomicInteger consumerEnables = new AtomicInteger();
        PluginLifecycleCoordinator coordinator = new PluginLifecycleCoordinator(context -> {
            if (context.candidate().pluginId().equals(new PluginId("provider"))) {
                String version = context.candidate().descriptor().version().value();
                context.services().provide(new ServiceContract("profile", new SemanticVersion("1.0.0")),
                        (operation, arguments) -> CompletableFuture.completedFuture(version));
            } else {
                consumerContext[0] = context;
            }
            return CompletableFuture.completedFuture(new NoopRuntime(
                    context.candidate().pluginId().equals(new PluginId("consumer")) ? consumerEnables : null));
        }, new ResourceRegistry(), Duration.ofSeconds(1), Duration.ofSeconds(1),
                QuarantinePolicy.DEFAULT, Runnable::run);
        InstalledPluginCandidate provider = TestCandidates.candidate("provider");
        InstalledPluginCandidate consumer = TestCandidates.candidate("consumer", "1.0.0", """
                {"required":{"provider":"*"},"optional":{},"loadBefore":[],"loadAfter":[]}
                """);
        coordinator.install(List.of(provider, consumer));
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        PluginServiceProxy proxy = consumerContext[0].services().acquire(
                "profile", new SemverRange("^1.0.0"), DependentReloadPolicy.RELOAD);

        assertEquals("1.0.0", proxy.invoke("version", List.of()).toCompletableFuture().join());
        coordinator.replace(TestCandidates.candidate("provider", "2.0.0", TestCandidates.emptyDependencies()),
                UUID.randomUUID()).toCompletableFuture().join();
        assertEquals("2.0.0", proxy.invoke("version", List.of()).toCompletableFuture().join());
        assertEquals(2, consumerEnables.get());
    }

    @Test
    void versionedEventsDeliverOnlyToCompatibleActiveSubscribers() {
        CrossPluginEventBus bus = new CrossPluginEventBus();
        InvocationAdmission admission = new InvocationAdmission(new PluginId("listener"));
        ResourceRegistry resources = new ResourceRegistry();
        UUID generation = UUID.randomUUID();
        PluginEvents events = bus.scoped(generation, new PluginId("listener"), admission, resources);
        AtomicInteger deliveries = new AtomicInteger();
        events.subscribe("user.changed", new SemverRange("^1.0.0"), payload -> {
            deliveries.incrementAndGet();
            return CompletableFuture.completedFuture(null);
        });
        admission.open();
        bus.activate(generation);

        events.publish(new EventContract("user.changed", new SemanticVersion("1.4.0")), "yes")
                .toCompletableFuture().join();
        events.publish(new EventContract("user.changed", new SemanticVersion("2.0.0")), "no")
                .toCompletableFuture().join();
        bus.deactivate(generation);
        events.publish(new EventContract("user.changed", new SemanticVersion("1.4.0")), "no")
                .toCompletableFuture().join();

        assertEquals(1, deliveries.get());
        assertEquals(1, admission.snapshot().completed());
    }

    @Test
    void dependentReloadPolicyIsExplicitPerStableProxy() {
        CrossPluginServiceRegistry registry = new CrossPluginServiceRegistry();
        ResourceRegistry providerResources = new ResourceRegistry();
        InvocationAdmission providerAdmission = new InvocationAdmission(new PluginId("provider"));
        UUID providerGeneration = UUID.randomUUID();
        registry.scoped(providerGeneration, new PluginId("provider"), providerAdmission, providerResources)
                .provide(new ServiceContract("profile", new SemanticVersion("1.0.0")),
                        (operation, arguments) -> CompletableFuture.completedFuture(null));
        registry.activate(providerGeneration);
        registry.scoped(UUID.randomUUID(), new PluginId("stable"),
                new InvocationAdmission(new PluginId("stable")), new ResourceRegistry())
                .acquire("profile", new SemverRange("*"), DependentReloadPolicy.KEEP_RUNNING);
        registry.scoped(UUID.randomUUID(), new PluginId("reload"),
                new InvocationAdmission(new PluginId("reload")), new ResourceRegistry())
                .acquire("profile", new SemverRange("*"), DependentReloadPolicy.RELOAD);

        assertEquals(java.util.Set.of(new PluginId("reload")),
                registry.reloadDependents(new PluginId("provider")));
    }

    @Test
    void optionalTransportCorrelatesValidatedResponseAndIsUnavailableWithoutCarrier() {
        OptionalProxyTransport transport = new OptionalProxyTransport(Duration.ofSeconds(1));
        ProxyMessageCodec codec = new ProxyMessageCodec();
        ProxyMessageEnvelope request = ProxyMessageEnvelope.request(UUID.randomUUID(), "example/status", "1.0.0",
                "status", new byte[] {1});
        byte[] encodedRequest = codec.encode(request);
        assertFalse(transport.request(encodedRequest).toCompletableFuture().join().available());
        byte[][] sent = new byte[1][];
        transport.carrier(payload -> {
            sent[0] = payload;
            return true;
        });

        CompletableFuture<OptionalProxyTransport.Response> response = transport.request(encodedRequest)
                .toCompletableFuture();
        assertArrayEquals(encodedRequest, sent[0]);
        byte[] encodedResponse = codec.encode(ProxyMessageEnvelope.success(request.requestId(), new byte[] {9}));
        assertFalse(transport.receive(encodedResponse, false));
        assertFalse(response.isDone());
        assertTrue(transport.receive(encodedResponse, true));
        assertTrue(response.join().available());
        assertArrayEquals(encodedResponse, response.join().payload());
    }

    private static final class NoopRuntime implements PluginRuntime {
        private final AtomicInteger enables;

        private NoopRuntime() {
            this(null);
        }

        private NoopRuntime(AtomicInteger enables) {
            this.enables = enables;
        }

        @Override public java.util.concurrent.CompletionStage<Void> load() { return done(); }
        @Override public java.util.concurrent.CompletionStage<Void> enable() {
            if (enables != null) {
                enables.incrementAndGet();
            }
            return done();
        }
        @Override public java.util.concurrent.CompletionStage<Void> ready() { return done(); }
        @Override public java.util.concurrent.CompletionStage<Void> drain() { return done(); }
        @Override public java.util.concurrent.CompletionStage<Void> disable() { return done(); }
        @Override public java.util.concurrent.CompletionStage<Void> unload() { return done(); }

        private static CompletableFuture<Void> done() {
            return CompletableFuture.completedFuture(null);
        }
    }
}
