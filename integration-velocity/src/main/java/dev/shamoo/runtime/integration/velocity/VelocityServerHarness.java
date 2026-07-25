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
        Path scriptPlugins = work.resolve("script-plugins");
        deleteTree(scriptPlugins);
        Files.createDirectories(scriptPlugins);
        createScriptPlugin(scriptPlugins, "fixture-one");
        createScriptPlugin(scriptPlugins, "fixture-two");
        createScriptPlugin(scriptPlugins, "corrupt");
        Files.writeString(scriptPlugins.resolve("corrupt/index.js.map"), "{\"version\":2}",
                StandardCharsets.UTF_8);
        Files.writeString(work.resolve("velocity.toml"), "bind = \"127.0.0.1:0\"\n",
                StandardCharsets.UTF_8);
        Files.copy(Path.of(arguments[1]), plugins.resolve("ShamooRuntime.jar"), StandardCopyOption.REPLACE_EXISTING);
        Process process = new ProcessBuilder(javaExecutable(), "-Xms256m", "-Xmx768m",
                "-Dshamoo.plugins.directory=" + scriptPlugins.toAbsolutePath(), "-jar",
                Path.of(arguments[0]).toAbsolutePath().toString())
                .directory(work.toFile()).redirectErrorStream(true).start();
        CountDownLatch ready = new CountDownLatch(2);
        Path log = work.resolve("harness.log");
        Thread output = Thread.ofPlatform().start(() -> readOutput(process, log, ready));
        try {
            if (!ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Velocity did not initialize ShamooRuntime; inspect " + log);
            }
            awaitLog(log, "Plugin admission rejected for corrupt");
            awaitLog(log, "ShamooRuntime initialized with protocol");
            Files.writeString(scriptPlugins.resolve("corrupt/index.js.map"),
                    "{\"version\":3,\"sources\":[\"src/plugin.ts\"],\"mappings\":\"AAAA\"}",
                    StandardCharsets.UTF_8);
            awaitLog(log, "SHAMOO_FIXTURE_LIFECYCLE corrupt");
            verifyRuntimeCommands(process, log);
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

    private static void createScriptPlugin(Path plugins, String name) throws IOException {
        Path root = plugins.resolve(name);
        Files.createDirectories(root);
        Files.writeString(root.resolve("index.js"), "export default Object.freeze({start(){"
                + "console.log('SHAMOO_FIXTURE_LIFECYCLE " + name + "')}});\n", StandardCharsets.UTF_8);
        Files.writeString(root.resolve("index.js.map"),
                "{\"version\":3,\"sources\":[\"src/plugin.ts\"],\"mappings\":\"AAAA\"}",
                StandardCharsets.UTF_8);
        Files.writeString(root.resolve("shamoo-plugin.json"), manifest(name), StandardCharsets.UTF_8);
    }

    private static String manifest(String name) {
        return "{\"name\":\"" + name + "\",\"displayName\":\"" + name + "\",\"version\":\"1.0.0\","
                + "\"shamoo\":{\"api\":\"^0.1.0\",\"runtime\":\"^0.1.0\",\"manifest\":2},"
                + "\"platforms\":{\"paper\":{\"enabled\":false},\"velocity\":{\"enabled\":true,"
                + "\"velocityApi\":\"3.x\"}},\"dependencies\":{"
                + "\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]},\"node\":{"
                + "\"builtins\":[],\"filesystem\":{\"read\":[],\"write\":[]},\"network\":false,"
                + "\"workers\":false,\"childProcess\":false,\"nativeAddons\":false},\"reload\":{"
                + "\"watch\":true,\"debounceMs\":100,\"preserveState\":true},\"compiler\":{"
                + "\"version\":\"process-fixture\",\"components\":[],\"modules\":[],"
                + "\"communication\":{\"services\":[],\"events\":[],\"consumers\":[]}}}";
    }

    private static void verifyRuntimeCommands(Process process, Path log) throws IOException, InterruptedException {
        process.getOutputStream().write("ping\nplugins\n".getBytes(StandardCharsets.UTF_8));
        process.getOutputStream().flush();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log)) {
                String output = Files.readString(log);
                if (output.contains("pong") && output.contains("Velocity Plugins")
                        && output.contains("Shamoo Plugins (3):")
                        && output.contains("Plugin admission rejected for corrupt")) {
                    return;
                }
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Velocity runtime commands were not observed; inspect " + log);
    }

    private static void awaitLog(Path log, String expected) throws IOException, InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains(expected)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Expected log entry was not observed: " + expected + "; inspect " + log);
    }

    private static void readOutput(Process process, Path log, CountDownLatch ready) {
        java.util.Set<String> loaded = new java.util.HashSet<>();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream(),
                StandardCharsets.UTF_8)); var writer = Files.newBufferedWriter(log, StandardCharsets.UTF_8)) {
            String line = reader.readLine();
            while (line != null) {
                writer.write(line);
                writer.newLine();
                writer.flush();
                for (String fixture : java.util.List.of("fixture-one", "fixture-two")) {
                    if (line.contains("SHAMOO_FIXTURE_LIFECYCLE " + fixture) && loaded.add(fixture)) {
                        ready.countDown();
                    }
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

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (var paths = Files.walk(root)) {
            for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }
}
