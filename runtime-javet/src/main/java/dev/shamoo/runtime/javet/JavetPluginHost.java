package dev.shamoo.runtime.javet;

import dev.shamoo.runtime.core.InstalledPluginCandidate;
import dev.shamoo.runtime.core.PlatformCapabilities;
import dev.shamoo.runtime.core.PluginDirectoryWatcher;
import dev.shamoo.runtime.core.PluginDiscovery;
import dev.shamoo.runtime.core.PluginDiscoveryResult;
import dev.shamoo.runtime.core.PluginId;
import dev.shamoo.runtime.core.PluginIntrospectionSnapshot;
import dev.shamoo.runtime.core.PluginLifecycleCoordinator;
import dev.shamoo.runtime.core.QuarantinePolicy;
import dev.shamoo.runtime.core.ResourceRegistry;
import dev.shamoo.runtime.protocol.CompatibilityInput;
import dev.shamoo.runtime.protocol.CompatibilityNegotiator;
import dev.shamoo.runtime.protocol.CompatibilityResult;
import dev.shamoo.runtime.protocol.ManifestCodec;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.Comparator;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Function;

/** Production composition root for discovery, compatibility, Javet generations, and lifecycle administration. */
public final class JavetPluginHost implements AutoCloseable {
    private final Path pluginsDirectory;
    private final Path stagingDirectory;
    private final CompatibilityInput compatibility;
    private final PluginDiscovery discovery;
    private final ShamooNodeRuntimeManager runtimeManager = new ShamooNodeRuntimeManager();
    private final PluginLifecycleCoordinator coordinator;
    private final ExecutorService administration = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = Thread.ofPlatform().unstarted(runnable);
        thread.setName("shamoo-plugin-administration");
        thread.setDaemon(true);
        return thread;
    });
    private final Map<PluginId, InstalledPluginCandidate> candidates = new LinkedHashMap<>();
    private final Map<Path, PluginId> sourceIdentities = new LinkedHashMap<>();
    private final System.Logger logger;
    private PluginDirectoryWatcher watcher;
    private boolean started;
    private boolean closed;

    public JavetPluginHost(
            Path pluginsDirectory,
            CompatibilityInput compatibility,
            PlatformCapabilities platformCapabilities,
            Duration stabilityWindow,
            Duration hookTimeout,
            Duration drainTimeout,
            Function<dev.shamoo.runtime.core.PluginRuntimeContext, Map<String, HostFunction>> bindings,
            System.Logger logger) {
        this.pluginsDirectory = Objects.requireNonNull(pluginsDirectory, "pluginsDirectory")
                .toAbsolutePath().normalize();
        stagingDirectory = this.pluginsDirectory.resolve(".shamoo-staging");
        this.compatibility = Objects.requireNonNull(compatibility, "compatibility");
        this.logger = Objects.requireNonNull(logger, "logger");
        discovery = new PluginDiscovery(Objects.requireNonNull(stabilityWindow, "stabilityWindow"));
        JavetPluginRuntimeFactory factory = new JavetPluginRuntimeFactory(
                runtimeManager, ShamooNodeRuntimeOptions.DEFAULT, bindings,
                context -> error -> logger.log(System.Logger.Level.ERROR,
                        "Unhandled plugin runtime error for " + context.candidate().pluginId(), error),
                (context, runtime) -> new JavetPluginRuntime(context, runtime, compatibility.platform()));
        coordinator = new PluginLifecycleCoordinator(factory, new ResourceRegistry(), hookTimeout, drainTimeout,
                QuarantinePolicy.DEFAULT, administration, platformCapabilities);
    }

    public synchronized void start(Duration watcherQuietWindow) throws IOException {
        if (started || closed) {
            throw new IllegalStateException(started ? "plugin host is already started" : "plugin host is closed");
        }
        Files.createDirectories(pluginsDirectory);
        deleteTree(stagingDirectory);
        PluginDiscoveryResult result = discovery.discover(pluginsDirectory);
        result.errors().forEach(error -> logger.log(System.Logger.Level.ERROR, error.getMessage(), error));
        for (InstalledPluginCandidate candidate : result.candidates()) {
            admit(candidate);
        }
        coordinator.install(candidates.values());
        coordinator.enableAll(UUID.randomUUID()).toCompletableFuture().join();
        indexSources();
        watcher = new PluginDirectoryWatcher(pluginsDirectory, watcherQuietWindow, this::watcherChanged,
                error -> logger.log(System.Logger.Level.ERROR, "Plugin directory watcher failed", error));
        watcher.start();
        started = true;
    }

    public CompletionStage<Void> install(Path source) {
        return submit(() -> installNow(source));
    }

    public CompletionStage<Void> enable(PluginId pluginId) {
        return submit(() -> coordinator.enable(pluginId, UUID.randomUUID())
                .thenCompose(ignored -> coordinator.ready(pluginId, UUID.randomUUID())));
    }

    public CompletionStage<Void> disable(PluginId pluginId) {
        return submit(() -> coordinator.disable(pluginId, UUID.randomUUID()));
    }

    public CompletionStage<Void> unload(PluginId pluginId) {
        return submit(() -> coordinator.unload(pluginId, UUID.randomUUID()).thenRun(() -> {
            InstalledPluginCandidate removed;
            synchronized (this) {
                removed = candidates.remove(pluginId);
                coordinator.install(candidates.values());
            }
            if (removed != null) {
                deleteSnapshot(removed.root());
            }
        }));
    }

    public CompletionStage<Void> reload(PluginId pluginId) {
        return submit(() -> {
            Path source;
            synchronized (this) {
                source = sourceIdentities.entrySet().stream()
                        .filter(entry -> entry.getValue().equals(pluginId)).map(Map.Entry::getKey)
                        .findFirst().orElseThrow(() -> new IllegalArgumentException(
                                "no source directory for plugin: " + pluginId));
            }
            return installNow(source);
        });
    }

    public List<PluginIntrospectionSnapshot> snapshots() {
        return coordinator.snapshots();
    }

    public int runtimeCount() {
        return runtimeManager.size();
    }

    private CompletionStage<Void> installNow(Path source) {
        try {
            InstalledPluginCandidate candidate = discovery.stage(source, stagingDirectory);
            admitCompatibility(candidate);
            InstalledPluginCandidate previous;
            synchronized (this) {
                previous = candidates.get(candidate.pluginId());
            }
            CompletionStage<Void> operation;
            boolean unloaded = previous != null && coordinator.snapshot(candidate.pluginId()).state()
                    == dev.shamoo.runtime.core.PluginLifecycleState.UNLOADED;
            if (previous == null || unloaded) {
                synchronized (this) {
                    candidates.put(candidate.pluginId(), candidate);
                    sourceIdentities.put(source.toAbsolutePath().normalize(), candidate.pluginId());
                    coordinator.install(candidates.values());
                }
                operation = coordinator.load(candidate.pluginId(), UUID.randomUUID())
                        .thenCompose(ignored -> coordinator.enable(candidate.pluginId(), UUID.randomUUID()))
                        .thenCompose(ignored -> coordinator.ready(candidate.pluginId(), UUID.randomUUID()));
            } else {
                operation = coordinator.replace(candidate, UUID.randomUUID()).thenRun(() -> {
                    synchronized (this) {
                        candidates.put(candidate.pluginId(), candidate);
                        sourceIdentities.put(source.toAbsolutePath().normalize(), candidate.pluginId());
                    }
                });
            }
            return operation.handle((ignored, failure) -> {
                if (failure == null) {
                    if (previous != null) {
                        deleteSnapshot(previous.root());
                    }
                } else {
                    deleteSnapshot(candidate.root());
                    throw new java.util.concurrent.CompletionException(failure);
                }
                return null;
            });
        } catch (IOException | RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private void watcherChanged(Path source) {
        submit(() -> {
            if (Files.isDirectory(source)) {
                return installNow(source);
            }
            PluginId removed;
            synchronized (this) {
                removed = sourceIdentities.remove(source.toAbsolutePath().normalize());
            }
            if (removed == null) {
                return CompletableFuture.completedFuture(null);
            }
            return coordinator.disable(removed, UUID.randomUUID())
                    .thenCompose(ignored -> coordinator.unload(removed, UUID.randomUUID()))
                    .thenRun(() -> {
                        InstalledPluginCandidate candidate;
                        synchronized (this) {
                            candidate = candidates.remove(removed);
                            coordinator.install(candidates.values());
                        }
                        if (candidate != null) {
                            deleteSnapshot(candidate.root());
                        }
                    });
        }).exceptionally(failure -> {
            logger.log(System.Logger.Level.ERROR, "Plugin directory transaction failed for " + source, failure);
            return null;
        });
    }

    private synchronized void admit(InstalledPluginCandidate candidate) {
        admitCompatibility(candidate);
        candidates.put(candidate.pluginId(), candidate);
    }

    private void admitCompatibility(InstalledPluginCandidate candidate) {
        CompatibilityResult result = new CompatibilityNegotiator().negotiate(candidate.descriptor(), compatibility);
        if (!result.compatible()) {
            throw new IllegalArgumentException("plugin " + candidate.pluginId() + " is incompatible: "
                    + result.reasons());
        }
        ShamooPluginMetadata.load(candidate.root(), candidate.descriptor(), compatibility.platform());
    }

    private void indexSources() throws IOException {
        ManifestCodec codec = new ManifestCodec();
        try (var paths = Files.list(pluginsDirectory)) {
            for (Path path : paths.filter(Files::isDirectory)
                    .filter(path -> !path.equals(stagingDirectory)).toList()) {
                try {
                    String descriptorJson = Files.readString(path.resolve(PluginDiscovery.DESCRIPTOR_FILE));
                    PluginId pluginId = new PluginId(codec.parse(descriptorJson).name());
                    if (candidates.containsKey(pluginId)) {
                        sourceIdentities.put(path.toAbsolutePath().normalize(), pluginId);
                    }
                } catch (IOException | RuntimeException exception) {
                    logger.log(System.Logger.Level.WARNING, "Unable to index plugin source " + path, exception);
                }
            }
        }
    }

    private CompletionStage<Void> submit(
            java.util.function.Supplier<CompletionStage<Void>> operation) {
        synchronized (this) {
            if (closed) {
                return CompletableFuture.failedFuture(new IllegalStateException("plugin host is closed"));
            }
        }
        return CompletableFuture.completedFuture(null).thenComposeAsync(ignored -> operation.get(), administration);
    }

    @Override
    public void close() {
        synchronized (this) {
            if (closed) {
                return;
            }
            closed = true;
        }
        if (watcher != null) {
            try {
                watcher.close();
            } catch (IOException exception) {
                logger.log(System.Logger.Level.WARNING, "Unable to close plugin watcher", exception);
            }
        }
        RuntimeException failure = null;
        try {
            coordinator.disableAll(UUID.randomUUID()).toCompletableFuture().join();
            for (PluginIntrospectionSnapshot snapshot : coordinator.snapshots().reversed()) {
                coordinator.unload(snapshot.pluginId(), UUID.randomUUID()).toCompletableFuture().join();
            }
        } catch (RuntimeException exception) {
            failure = exception;
        }
        try {
            runtimeManager.close();
        } catch (RuntimeException exception) {
            if (failure == null) {
                failure = exception;
            } else {
                failure.addSuppressed(exception);
            }
        } finally {
            administration.shutdownNow();
            deleteTree(stagingDirectory);
        }
        if (failure != null) {
            throw failure;
        }
    }

    private void deleteSnapshot(Path snapshot) {
        Path normalized = snapshot.toAbsolutePath().normalize();
        if (!normalized.startsWith(stagingDirectory) || normalized.equals(stagingDirectory)) {
            throw new IllegalArgumentException("refusing to delete non-snapshot path: " + snapshot);
        }
        deleteTree(normalized);
    }

    private static void deleteTree(Path root) {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException exception) {
            throw new IllegalStateException("unable to remove obsolete staging snapshot " + root, exception);
        }
    }
}
