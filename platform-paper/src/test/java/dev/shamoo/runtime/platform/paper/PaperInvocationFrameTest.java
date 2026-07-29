package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.CloseResource", "PMD.CompareObjectsWithEquals",
        "PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts"})
class PaperInvocationFrameTest {
    @Test
    void executesNestedApiCallsOnTheOriginThread() throws Exception {
        PaperInvocationFrame frame = new PaperInvocationFrame("frame", Duration.ofSeconds(1), 4);
        Thread origin = Thread.currentThread();
        CompletableFuture<Object> callback = new CompletableFuture<>();
        Thread script = new Thread(() -> frame.call(() -> Thread.currentThread() == origin ? 42 : -1)
                .whenComplete((value, failure) -> {
                    if (failure == null) {
                        callback.complete(value);
                    } else {
                        callback.completeExceptionally(failure);
                    }
                }));

        script.start();
        assertEquals(42, frame.await(callback));
        script.join();
        frame.close();
    }

    @Test
    void expiresBlockedCallbacksAtTheFrameDeadline() {
        PaperInvocationFrame frame = new PaperInvocationFrame("frame", Duration.ofMillis(5), 1);
        CompletableFuture<Object> callback = new CompletableFuture<>();

        assertThrows(TimeoutException.class, () -> frame.await(callback));
        assertThrows(CompletionException.class, () -> callback.join());
        frame.close();
    }
}
