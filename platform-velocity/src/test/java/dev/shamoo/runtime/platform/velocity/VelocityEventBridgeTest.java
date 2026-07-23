package dev.shamoo.runtime.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

class VelocityEventBridgeTest {
    @Test
    void adaptsCompletionStageWithoutCallingToCompletableFuture() {
        CompletableFuture<Void> source = new CompletableFuture<>() {
            @Override
            public CompletableFuture<Void> toCompletableFuture() {
                throw new UnsupportedOperationException("not supported");
            }
        };
        CompletableFuture<Void> adapted = VelocityEventBridge.Subscription.adapt(source);

        source.complete(null);

        assertDoesNotThrow(adapted::join, "adapter must use CompletionStage.whenComplete");
    }
}
