package dev.shamoo.runtime.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({
    "PMD.UnitTestAssertionsShouldIncludeMessage",
    "PMD.UnitTestContainsTooManyAsserts"
})
class PluginDirectoryWatcherTest {
    @TempDir
    Path pluginsDirectory;

    @Test
    void coalescesNestedChangesUntilCandidateIsStable() throws Exception {
        Path candidate = Files.createDirectory(pluginsDirectory.resolve("plugin"));
        List<Path> observed = new CopyOnWriteArrayList<>();
        List<Exception> errors = new CopyOnWriteArrayList<>();
        try (PluginDirectoryWatcher watcher = new PluginDirectoryWatcher(
                pluginsDirectory, Duration.ofMillis(100), observed::add, errors::add)) {
            watcher.start();
            Path source = candidate.resolve("index.mjs");
            Files.writeString(source, "one");
            Thread.sleep(60);
            Files.writeString(source, "two");
            Thread.sleep(60);
            assertTrue(observed.isEmpty());
            await(() -> observed.size() == 1);
        }

        assertEquals(List.of(candidate.toAbsolutePath()), observed);
        assertTrue(errors.isEmpty());
    }

    private static void await(Supplier<Boolean> condition) throws InterruptedException {
        long deadline = System.nanoTime() + Duration.ofSeconds(3).toNanos();
        while (!condition.get() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue(condition.get());
    }
}
