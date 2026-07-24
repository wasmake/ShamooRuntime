package dev.shamoo.runtime.platform.velocity;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.Test;

@SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
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

        assertSame(source, adapted, "CompletableFuture dispatch must not allocate an adapter future");
        source.complete(null);

        assertDoesNotThrow(adapted::join, "adapter must use CompletionStage.whenComplete");
    }
}
