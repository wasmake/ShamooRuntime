package dev.shamoo.runtime.core;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardWatchEventKinds;
import java.nio.file.WatchEvent;
import java.nio.file.WatchKey;
import java.nio.file.WatchService;
import java.time.Duration;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Recursively observes plugin directories and emits one candidate path after a quiet window. */
public final class PluginDirectoryWatcher implements AutoCloseable {
    private static final String STAGING_DIRECTORY = ".shamoo-staging";
    private final Path root;
    private final Duration quietWindow;
    private final Consumer<Path> listener;
    private final Consumer<Exception> errorListener;
    private final WatchService watchService;
    private final ScheduledExecutorService executor;
    private final Map<WatchKey, Path> watchedDirectories = new HashMap<>();
    private final Map<Path, ScheduledFuture<?>> pending = new HashMap<>();
    private volatile boolean closed;
    private boolean started;

    public PluginDirectoryWatcher(
            Path pluginsDirectory,
            Duration quietWindow,
            Consumer<Path> listener,
            Consumer<Exception> errorListener) throws IOException {
        root = Objects.requireNonNull(pluginsDirectory, "pluginsDirectory").toAbsolutePath().normalize();
        this.quietWindow = Objects.requireNonNull(quietWindow, "quietWindow");
        this.listener = Objects.requireNonNull(listener, "listener");
        this.errorListener = Objects.requireNonNull(errorListener, "errorListener");
        if (quietWindow.isNegative()) {
            throw new IllegalArgumentException("quietWindow must not be negative");
        }
        if (!Files.isDirectory(root)) {
            throw new IOException("plugins directory is not a directory: " + root);
        }
        watchService = FileSystems.getDefault().newWatchService();
        executor = Executors.newScheduledThreadPool(2, runnable -> {
            Thread thread = Thread.ofPlatform().unstarted(runnable);
            thread.setName("shamoo-plugin-watcher");
            thread.setDaemon(true);
            return thread;
        });
        registerTree(root);
    }

    /** Starts observation. Calling this more than once has no effect. */
    public synchronized void start() {
        if (closed) {
            throw new IllegalStateException("watcher is closed");
        }
        if (!started) {
            started = true;
            executor.execute(this::watchLoop);
        }
    }

    private void watchLoop() {
        while (!closed) {
            try {
                process(watchService.take());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            } catch (RuntimeException exception) {
                if (!closed) {
                    errorListener.accept(exception);
                }
            }
        }
    }

    private synchronized void process(WatchKey key) {
        Path directory = watchedDirectories.get(key);
        if (directory == null) {
            key.reset();
            return;
        }
        for (WatchEvent<?> event : key.pollEvents()) {
            if (event.kind() == StandardWatchEventKinds.OVERFLOW) {
                directChildren().forEach(this::schedule);
                continue;
            }
            Path changed = directory.resolve((Path) event.context()).toAbsolutePath().normalize();
            if (event.kind() == StandardWatchEventKinds.ENTRY_CREATE && Files.isDirectory(changed)) {
                try {
                    registerTree(changed);
                } catch (IOException exception) {
                    errorListener.accept(exception);
                }
            }
            Path candidate = candidateRoot(changed);
            if (candidate != null) {
                schedule(candidate);
            }
        }
        if (!key.reset()) {
            watchedDirectories.remove(key);
        }
    }

    private Path candidateRoot(Path changed) {
        if (!changed.startsWith(root) || changed.equals(root)) {
            return null;
        }
        Path first = root.relativize(changed).getName(0);
        return STAGING_DIRECTORY.equals(first.toString()) ? null : root.resolve(first);
    }

    private synchronized void schedule(Path candidate) {
        ScheduledFuture<?> previous = pending.remove(candidate);
        if (previous != null) {
            previous.cancel(false);
        }
        pending.put(candidate, executor.schedule(() -> emit(candidate), quietWindow.toMillis(), TimeUnit.MILLISECONDS));
    }

    private void emit(Path candidate) {
        synchronized (this) {
            pending.remove(candidate);
        }
        // Missing candidates are deliberate delete notifications; consumers retain the identity mapping.
        if (!closed) {
            listener.accept(candidate);
        }
    }

    private void registerTree(Path directory) throws IOException {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.filter(Files::isDirectory).toList()) {
                WatchKey key = path.register(watchService,
                        StandardWatchEventKinds.ENTRY_CREATE,
                        StandardWatchEventKinds.ENTRY_MODIFY,
                        StandardWatchEventKinds.ENTRY_DELETE);
                watchedDirectories.put(key, path);
            }
        }
    }

    private java.util.List<Path> directChildren() {
        try (var paths = Files.list(root)) {
            return paths.filter(Files::isDirectory)
                    .filter(path -> !STAGING_DIRECTORY.equals(path.getFileName().toString())).toList();
        } catch (IOException exception) {
            errorListener.accept(exception);
            return java.util.List.of();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        pending.values().forEach(future -> future.cancel(false));
        pending.clear();
        watchService.close();
        executor.shutdownNow();
    }
}
