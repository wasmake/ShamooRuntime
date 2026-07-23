package dev.shamoo.runtime.core;

import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;

/** Thread-safe first/last registration ownership for shared native resources. */
@SuppressWarnings("PMD.AvoidLiteralsInIfCondition")
public final class RegistrationRefCounter<K> {
    private final Map<K, Integer> references = new HashMap<>();

    public AutoCloseable acquire(K key, Runnable register, Runnable unregister) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(register, "register");
        Objects.requireNonNull(unregister, "unregister");
        synchronized (references) {
            if (references.merge(key, 1, Integer::sum) == 1) {
                try {
                    register.run();
                } catch (RuntimeException failure) {
                    references.remove(key);
                    throw failure;
                }
            }
        }
        AtomicBoolean closed = new AtomicBoolean();
        return () -> {
            if (!closed.compareAndSet(false, true)) {
                return;
            }
            synchronized (references) {
                int remaining = references.getOrDefault(key, 0) - 1;
                if (remaining == 0) {
                    references.remove(key);
                    unregister.run();
                } else if (remaining > 0) {
                    references.put(key, remaining);
                }
            }
        };
    }
}
