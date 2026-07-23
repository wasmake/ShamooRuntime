package dev.shamoo.runtime.integration.velocity;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Launches a pinned Velocity process, verifies bootstrap readiness, and stops it cleanly. */
public final class VelocityServerHarness {
    private static final int ARGUMENT_COUNT = 3;
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);

    private VelocityServerHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != ARGUMENT_COUNT) {
            throw new IllegalArgumentException("usage: <server.jar> <plugin.jar> <work-directory>");
        }
        Path work = Path.of(arguments[2]);
        Path plugins = work.resolve("plugins");
        Files.createDirectories(plugins);
        Files.writeString(work.resolve("velocity.toml"), "bind = \"127.0.0.1:0\"\n",
                StandardCharsets.UTF_8);
        Files.copy(Path.of(arguments[1]), plugins.resolve("ShamooRuntime.jar"), StandardCopyOption.REPLACE_EXISTING);
        Process process = new ProcessBuilder(javaExecutable(), "-Xms256m", "-Xmx768m", "-jar",
                Path.of(arguments[0]).toAbsolutePath().toString())
                .directory(work.toFile()).redirectErrorStream(true).start();
        CountDownLatch ready = new CountDownLatch(1);
        Path log = work.resolve("harness.log");
        Thread output = Thread.ofPlatform().start(() -> readOutput(process, log, ready));
        try {
            if (!ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Velocity did not initialize ShamooRuntime; inspect " + log);
            }
        } finally {
            if (process.isAlive()) {
                process.getOutputStream().write("shutdown\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            output.join();
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Velocity exited with " + process.exitValue() + "; inspect " + log);
        }
    }

    private static void readOutput(Process process, Path log, CountDownLatch ready) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8)); var writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            while (line != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
                if (line.contains("ShamooRuntime initialized with protocol")) {
                    ready.countDown();
                }
                line = reader.readLine();
            }
        } catch (IOException exception) {
            throw new java.io.UncheckedIOException(exception);
        }
    }

    private static String javaExecutable() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }
}
