package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.SemverRange;
import dev.shamoo.runtime.protocol.ServiceContract;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;

/** Core-owned service directory with generation-aware providers and stable consumer proxies. */
@SuppressWarnings("PMD.CloseResource")
public final class CrossPluginServiceRegistry {
    private final Map<UUID, Generation> generations = new ConcurrentHashMap<>();
    private final Set<StableProxy> proxies = ConcurrentHashMap.newKeySet();
    private final Object lock = new Object();

    PluginServices scoped(UUID generationId, PluginId owner, InvocationController admission,
            ResourceRegistry resources) {
        Objects.requireNonNull(generationId, "generationId");
        Objects.requireNonNull(owner, "owner");
        Objects.requireNonNull(admission, "admission");
        Objects.requireNonNull(resources, "resources");
        synchronized (lock) {
            generations.put(generationId, new Generation(owner, admission, new ConcurrentHashMap<>()));
        }
        return new PluginServices() {
            @Override
            public AutoCloseable provide(ServiceContract contract, PluginServiceHandler handler) {
                return register(generationId, owner, resources, contract, handler);
            }

            @Override
            public PluginServiceProxy acquire(
                    String serviceName, SemverRange versions, DependentReloadPolicy reloadPolicy) {
                StableProxy proxy = new StableProxy(owner, serviceName, versions, reloadPolicy);
                proxies.add(proxy);
                return resources.register(owner, ResourceCategory.PROXY, serviceName, proxy);
            }
        };
    }

    void activate(UUID generationId) {
        synchronized (lock) {
            generation(generationId).active = true;
        }
    }

    void deactivate(UUID generationId) {
        synchronized (lock) {
            Generation generation = generations.get(generationId);
            if (generation != null) {
                generation.active = false;
            }
        }
    }

    void retire(UUID generationId) {
        deactivate(generationId);
        generations.remove(generationId);
    }

    Set<PluginId> reloadDependents(PluginId provider) {
        return proxies.stream().filter(proxy -> !proxy.closed.get())
                .filter(proxy -> proxy.reloadPolicy == DependentReloadPolicy.RELOAD)
                .filter(proxy -> hasProvider(provider, proxy.serviceName, proxy.versions))
                .map(proxy -> proxy.consumer).collect(java.util.stream.Collectors.toUnmodifiableSet());
    }

    private AutoCloseable register(UUID generationId, PluginId owner, ResourceRegistry resources,
            ServiceContract contract, PluginServiceHandler handler) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(handler, "handler");
        Generation generation = generation(generationId);
        if (generation.providers.putIfAbsent(contract, handler) != null) {
            throw new IllegalStateException("service already registered in generation: " + contract);
        }
        AtomicBoolean closed = new AtomicBoolean();
        return resources.register(owner, ResourceCategory.SERVICE, contract.toString(), () -> {
            if (closed.compareAndSet(false, true)) {
                generation.providers.remove(contract, handler);
            }
        });
    }

    private boolean hasProvider(PluginId owner, String name, SemverRange versions) {
        return generations.values().stream().anyMatch(generation -> generation.active
                && generation.owner.equals(owner) && generation.providers.keySet().stream()
                        .anyMatch(contract -> contract.name().equals(name) && versions.includes(contract.version())));
    }

    private Provider resolve(String name, SemverRange versions) {
        synchronized (lock) {
            List<Provider> candidates = new ArrayList<>();
            generations.values().stream().filter(generation -> generation.active).forEach(generation ->
                generation.providers.forEach((contract, handler) -> {
                    if (contract.name().equals(name) && versions.includes(contract.version())) {
                        candidates.add(new Provider(contract, generation, handler));
                    }
                }));
            return candidates.stream().max(Comparator.comparing(provider -> provider.contract.version(),
                    dev.shamoo.runtime.protocol.SemanticVersion::comparePrecedence)).orElse(null);
        }
    }

    private Generation generation(UUID generationId) {
        Generation generation = generations.get(generationId);
        if (generation == null) {
            throw new IllegalStateException("unknown plugin generation");
        }
        return generation;
    }

    private final class StableProxy implements PluginServiceProxy {
        private final PluginId consumer;
        private final String serviceName;
        private final SemverRange versions;
        private final DependentReloadPolicy reloadPolicy;
        private final AtomicBoolean closed = new AtomicBoolean();

        private StableProxy(PluginId consumer, String serviceName, SemverRange versions,
                DependentReloadPolicy reloadPolicy) {
            this.consumer = consumer;
            this.serviceName = ServiceContract.contractName(serviceName);
            this.versions = Objects.requireNonNull(versions, "versions");
            this.reloadPolicy = Objects.requireNonNull(reloadPolicy, "reloadPolicy");
        }

        @Override
        public CompletionStage<Object> invoke(String operation, List<Object> arguments) {
            UUID correlationId = UUID.randomUUID();
            if (closed.get()) {
                return CompletableFuture.failedFuture(new ServiceUnavailableException(serviceName, correlationId));
            }
            if (operation == null || !operation.matches("[A-Za-z_$][A-Za-z0-9_$]*")) {
                throw new IllegalArgumentException("invalid service operation: " + operation);
            }
            List<Object> copiedArguments = List.copyOf(arguments);
            Provider provider = resolve(serviceName, versions);
            if (provider == null) {
                return CompletableFuture.failedFuture(new ServiceUnavailableException(serviceName, correlationId));
            }
            InvocationAdmission.Lease lease;
            try {
                lease = provider.generation.admission.admit();
            } catch (InvocationRejectedError error) {
                return CompletableFuture.failedFuture(new ServiceUnavailableException(serviceName, correlationId));
            }
            CompletionStage<Object> invocation;
            try {
                invocation = Objects.requireNonNull(provider.handler.invoke(operation, copiedArguments),
                        "service handler returned null");
            } catch (RuntimeException exception) {
                lease.close();
                return CompletableFuture.failedFuture(
                        new ServiceInvocationException(serviceName, operation, correlationId, exception));
            }
            return invocation.handle((value, failure) -> {
                lease.close();
                if (failure != null) {
                    throw new java.util.concurrent.CompletionException(new ServiceInvocationException(
                            serviceName, operation, correlationId, failure));
                }
                return value;
            });
        }

        @Override
        public void close() {
            if (closed.compareAndSet(false, true)) {
                proxies.remove(this);
            }
        }
    }

    private static final class Generation {
        private final PluginId owner;
        private final InvocationController admission;
        private final Map<ServiceContract, PluginServiceHandler> providers;
        private volatile boolean active;

        private Generation(PluginId owner, InvocationController admission,
                Map<ServiceContract, PluginServiceHandler> providers) {
            this.owner = owner;
            this.admission = admission;
            this.providers = providers;
        }
    }

    private record Provider(ServiceContract contract, Generation generation, PluginServiceHandler handler) {
    }
}
