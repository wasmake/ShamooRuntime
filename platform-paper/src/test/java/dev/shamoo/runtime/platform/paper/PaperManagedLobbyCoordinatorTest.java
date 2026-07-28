package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.bukkit.Server;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.messaging.Messenger;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.CloseResource", "PMD.AvoidDuplicateLiterals"})
class PaperManagedLobbyCoordinatorTest {
    @Test
    void atomicallyHandsOffAndRestoresThePreviousGeneration() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Messenger messenger = mock(Messenger.class);
        PaperManagedLobbyBridge first = mock(PaperManagedLobbyBridge.class);
        PaperManagedLobbyBridge candidate = mock(PaperManagedLobbyBridge.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(messenger);
        PaperManagedLobbyCoordinator coordinator = new PaperManagedLobbyCoordinator(plugin);

        PaperManagedLobbyCoordinator.Activation firstActivation = coordinator.activate(first);
        assertNull(firstActivation.previous());
        assertTrue(firstActivation.cold());
        assertFalse(coordinator.isActive(first));
        coordinator.commit(first, firstActivation);
        assertTrue(coordinator.isActive(first));
        PaperManagedLobbyCoordinator.Activation candidateActivation = coordinator.activate(candidate);
        assertSame(first, candidateActivation.previous());
        assertFalse(candidateActivation.cold());
        assertTrue(coordinator.isActive(first));
        assertFalse(coordinator.isActive(candidate));
        verify(messenger, times(1)).registerOutgoingPluginChannel(plugin, "BungeeCord");

        coordinator.rollback(candidate, candidateActivation);
        assertTrue(coordinator.isActive(first));
        assertFalse(coordinator.isActive(candidate));
        verify(messenger, never()).unregisterOutgoingPluginChannel(plugin, "BungeeCord");

        assertNull(coordinator.deactivate(first));
        verify(messenger, times(1)).unregisterOutgoingPluginChannel(plugin, "BungeeCord");
        coordinator.close();
    }

    @Test
    void doesNotReclassifyReplacementGapAsCold() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Messenger messenger = mock(Messenger.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(messenger);
        PaperManagedLobbyCoordinator coordinator = new PaperManagedLobbyCoordinator(plugin);
        PaperManagedLobbyBridge first = mock(PaperManagedLobbyBridge.class);
        PaperManagedLobbyBridge replacement = mock(PaperManagedLobbyBridge.class);

        PaperManagedLobbyCoordinator.Activation firstActivation = coordinator.activate(first);
        assertTrue(firstActivation.cold());
        coordinator.commit(first, firstActivation);
        assertNull(coordinator.deactivate(first));
        PaperManagedLobbyCoordinator.Activation activation = coordinator.activate(replacement);

        assertFalse(activation.cold());
        assertNull(activation.previous());
        coordinator.close();
    }

    @Test
    void provisionalCandidateDoesNotBecomeActiveWhenPreviousCloses() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Messenger messenger = mock(Messenger.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(messenger);
        PaperManagedLobbyCoordinator coordinator = new PaperManagedLobbyCoordinator(plugin);
        PaperManagedLobbyBridge first = mock(PaperManagedLobbyBridge.class);
        PaperManagedLobbyBridge candidate = mock(PaperManagedLobbyBridge.class);
        PaperManagedLobbyCoordinator.Activation firstActivation = coordinator.activate(first);
        coordinator.commit(first, firstActivation);

        PaperManagedLobbyCoordinator.Activation candidateActivation = coordinator.activate(candidate);
        assertSame(first, candidateActivation.previous());
        assertNull(coordinator.deactivate(first));
        assertFalse(coordinator.isActive(candidate));

        coordinator.commit(candidate, candidateActivation);
        assertTrue(coordinator.isActive(candidate));
        assertFalse(candidateActivation.cold());
        coordinator.close();
    }

    @Test
    void registrationFailureLeavesCoordinatorUnchangedAndColdEligible() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Messenger messenger = mock(Messenger.class);
        PaperManagedLobbyBridge bridge = mock(PaperManagedLobbyBridge.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(messenger);
        doThrow(new IllegalStateException("registration failed")).doNothing()
                .when(messenger).registerOutgoingPluginChannel(plugin, "BungeeCord");
        PaperManagedLobbyCoordinator coordinator = new PaperManagedLobbyCoordinator(plugin);

        assertThrows(IllegalStateException.class, () -> coordinator.activate(bridge));
        assertFalse(coordinator.isActive(bridge));
        PaperManagedLobbyCoordinator.Activation activation = coordinator.activate(bridge);
        assertTrue(activation.cold());
        coordinator.commit(bridge, activation);
        assertTrue(coordinator.isActive(bridge));
        coordinator.close();
    }

    @Test
    void rolledBackFirstProvisionalActivationLeavesTrueRetryCold() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Messenger messenger = mock(Messenger.class);
        PaperManagedLobbyBridge failed = mock(PaperManagedLobbyBridge.class);
        PaperManagedLobbyBridge retry = mock(PaperManagedLobbyBridge.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(messenger);
        PaperManagedLobbyCoordinator coordinator = new PaperManagedLobbyCoordinator(plugin);

        PaperManagedLobbyCoordinator.Activation failedActivation = coordinator.activate(failed);
        assertTrue(failedActivation.cold());
        assertFalse(coordinator.isActive(failed));
        coordinator.rollback(failed, failedActivation);

        PaperManagedLobbyCoordinator.Activation retryActivation = coordinator.activate(retry);
        assertTrue(retryActivation.cold());
        assertFalse(coordinator.isActive(retry));
        coordinator.commit(retry, retryActivation);
        assertTrue(coordinator.isActive(retry));
        coordinator.close();
    }

    @Test
    void masksSameGenerationActivityUntilReloadCommitsOrRollsBack() {
        JavaPlugin plugin = mock(JavaPlugin.class);
        Server server = mock(Server.class);
        Messenger messenger = mock(Messenger.class);
        PaperManagedLobbyBridge bridge = mock(PaperManagedLobbyBridge.class);
        when(plugin.getServer()).thenReturn(server);
        when(server.getMessenger()).thenReturn(messenger);
        PaperManagedLobbyCoordinator coordinator = new PaperManagedLobbyCoordinator(plugin);
        PaperManagedLobbyCoordinator.Activation initial = coordinator.activate(bridge);
        coordinator.commit(bridge, initial);
        assertTrue(coordinator.isActive(bridge));

        PaperManagedLobbyCoordinator.Activation failedReload = coordinator.activate(bridge);
        assertFalse(coordinator.isActive(bridge));
        assertTrue(coordinator.ownsActive(bridge));
        coordinator.rollback(bridge, failedReload);
        assertTrue(coordinator.isActive(bridge));

        PaperManagedLobbyCoordinator.Activation successfulReload = coordinator.activate(bridge);
        assertFalse(coordinator.isActive(bridge));
        coordinator.commit(bridge, successfulReload);
        assertTrue(coordinator.isActive(bridge));
        coordinator.close();
    }
}
