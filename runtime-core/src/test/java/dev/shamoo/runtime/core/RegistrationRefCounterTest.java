package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
    "PMD.CloseResource",
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.UnitTestContainsTooManyAsserts"
})
class RegistrationRefCounterTest {
    @Test
    void registersFirstReferenceAndUnregistersLastExactlyOnce() throws Exception {
        RegistrationRefCounter<String> counter = new RegistrationRefCounter<>();
        AtomicInteger registrations = new AtomicInteger();
        AtomicInteger unregistrations = new AtomicInteger();
        AutoCloseable first = counter.acquire("channel", registrations::incrementAndGet,
                unregistrations::incrementAndGet);
        AutoCloseable second = counter.acquire("channel", registrations::incrementAndGet,
                unregistrations::incrementAndGet);

        first.close();
        first.close();
        assertEquals(1, registrations.get());
        assertEquals(0, unregistrations.get());
        second.close();
        assertEquals(1, unregistrations.get());
    }
}
