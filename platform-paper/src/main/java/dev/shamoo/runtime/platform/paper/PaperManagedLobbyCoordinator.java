package dev.shamoo.runtime.platform.paper;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import org.bukkit.plugin.java.JavaPlugin;

/** Arbitrates overlapping managed-lobby generations during transactional plugin replacement. */
@SuppressWarnings({"PMD.CompareObjectsWithEquals", "PMD.NullAssignment", "PMD.CloseResource"})
public final class PaperManagedLobbyCoordinator implements AutoCloseable {
    private static final String BUNGEE_CHANNEL = "BungeeCord";
    private final JavaPlugin plugin;
    private final Deque<PaperManagedLobbyBridge> generations = new ArrayDeque<>();
    private volatile PaperManagedLobbyBridge active;
    private volatile PaperManagedLobbyBridge provisional;
    private int channelLeases;
    private boolean everActivated;
    private boolean closed;

    public PaperManagedLobbyCoordinator(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
    }

    synchronized Activation activate(PaperManagedLobbyBridge bridge) {
        requireOpen();
        if (provisional != null) {
            throw new IllegalStateException("managed lobby activation is already in progress");
        }
        PaperManagedLobbyBridge previous = active;
        boolean added = !generations.contains(bridge);
        boolean registeredChannel = false;
        if (added) {
            if (channelLeases == 0) {
                plugin.getServer().getMessenger().registerOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
                registeredChannel = true;
            }
        }
        try {
            if (added) {
                generations.addLast(bridge);
                channelLeases++;
            }
            Activation activation = new Activation(previous, !everActivated, added);
            provisional = bridge;
            return activation;
        } catch (RuntimeException | Error failure) {
            provisional = null;
            if (added && generations.remove(bridge)) {
                channelLeases--;
            }
            if (registeredChannel) {
                try {
                    plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
                } catch (RuntimeException cleanupFailure) {
                    failure.addSuppressed(cleanupFailure);
                }
            }
            throw failure;
        }
    }

    synchronized void commit(PaperManagedLobbyBridge bridge, Activation activation) {
        requireOpen();
        PaperManagedLobbyBridge previous = activation.previous();
        boolean previousAvailable = previous == null ? active == null
                : active == previous || !generations.contains(previous);
        if (provisional != bridge || !generations.contains(bridge) || !previousAvailable) {
            throw new IllegalStateException("managed lobby activation is no longer current");
        }
        active = bridge;
        provisional = null;
        everActivated = true;
    }

    synchronized PaperManagedLobbyBridge rollback(PaperManagedLobbyBridge bridge, Activation activation) {
        if (provisional != bridge) {
            return active;
        }
        provisional = null;
        if (!activation.added() || !generations.remove(bridge)) {
            return active;
        }
        channelLeases--;
        if (channelLeases == 0) {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
        }
        return active;
    }

    synchronized PaperManagedLobbyBridge deactivate(PaperManagedLobbyBridge bridge) {
        if (!generations.remove(bridge)) {
            return active;
        }
        if (provisional == bridge) {
            provisional = null;
        }
        if (active == bridge) {
            active = lastCommittedGeneration();
        }
        if (channelLeases > 0) {
            channelLeases--;
            if (channelLeases == 0) {
                plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
            }
        }
        return active;
    }

    synchronized boolean isActive(PaperManagedLobbyBridge bridge) {
        return active == bridge && provisional != bridge;
    }

    synchronized boolean ownsActive(PaperManagedLobbyBridge bridge) {
        return active == bridge;
    }

    private PaperManagedLobbyBridge lastCommittedGeneration() {
        return generations.stream().filter(bridge -> bridge != provisional).reduce((first, second) -> second)
                .orElse(null);
    }

    record Activation(PaperManagedLobbyBridge previous, boolean cold, boolean added) {
    }

    private void requireOpen() {
        if (closed) {
            throw new IllegalStateException("managed lobby coordinator is closed");
        }
    }

    @Override
    public synchronized void close() {
        closed = true;
        generations.clear();
        active = null;
        provisional = null;
        if (channelLeases > 0) {
            plugin.getServer().getMessenger().unregisterOutgoingPluginChannel(plugin, BUNGEE_CHANNEL);
            channelLeases = 0;
        }
    }
}
