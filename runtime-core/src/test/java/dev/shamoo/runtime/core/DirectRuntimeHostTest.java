package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;

class DirectRuntimeHostTest {
    private static final String PLATFORM = "test";

    @Test
    void dispatchRunsTaskBeforeCompleting() {
        AtomicBoolean ran = new AtomicBoolean();
        DirectRuntimeHost host = new DirectRuntimeHost(PLATFORM, System.getLogger(getClass().getName()));

        host.dispatch(() -> ran.set(true)).toCompletableFuture().join();

        assertTrue(ran.get(), "dispatched task");
    }

    @Test
    void reportsConfiguredPlatformName() {
        DirectRuntimeHost host = new DirectRuntimeHost(PLATFORM, System.getLogger(getClass().getName()));

        assertEquals(PLATFORM, host.platformName(), "platform name");
    }
}
