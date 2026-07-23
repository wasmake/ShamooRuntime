package dev.shamoo.runtime.integration.paper;

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
import java.net.ServerSocket;
import java.net.Socket;
import java.io.OutputStream;
import java.io.InputStream;
import java.net.SocketTimeoutException;

/** Launches a pinned Paper process, verifies bootstrap readiness, and stops it cleanly. */
@SuppressWarnings({
    "PMD.AvoidUsingHardCodedIP",
    "PMD.CloseResource"
})
public final class PaperServerHarness {
    private static final int ARGUMENT_COUNT = 3;
    private static final Duration READY_TIMEOUT = Duration.ofMinutes(2);

    private PaperServerHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != ARGUMENT_COUNT) {
            throw new IllegalArgumentException("usage: <server.jar> <plugin.jar> <work-directory>");
        }
        Path work = Path.of(arguments[2]);
        Path plugins = work.resolve("plugins");
        Files.createDirectories(plugins);
        Path pluginData = plugins.resolve("ShamooRuntime");
        Files.createDirectories(pluginData);
        Files.writeString(pluginData.resolve("config.yml"), "packets:\n  enabled: true\n"
                + "  process-smoke: true\n  allowed-plugins: [shamooruntime]\n"
                + "  timeout-millis: 1000\n  maximum-pending: 32\n",
                StandardCharsets.UTF_8);
        int port;
        try (ServerSocket available = new ServerSocket(0)) {
            port = available.getLocalPort();
        }
        Files.writeString(work.resolve("eula.txt"), "eula=true\n", StandardCharsets.UTF_8);
        Files.writeString(work.resolve("server.properties"), "server-port=" + port + "\nonline-mode=false\n",
                StandardCharsets.UTF_8);
        Files.copy(Path.of(arguments[1]), plugins.resolve("ShamooRuntime.jar"), StandardCopyOption.REPLACE_EXISTING);
        Process process = new ProcessBuilder(javaExecutable(), "-Xms512m", "-Xmx1g", "-jar",
                Path.of(arguments[0]).toAbsolutePath().toString(), "--nogui")
                .directory(work.toFile()).redirectErrorStream(true).start();
        CountDownLatch ready = new CountDownLatch(1);
        Path log = work.resolve("harness.log");
        Thread output = Thread.ofPlatform().start(() -> readOutput(process, log, ready));
        try {
            if (!ready.await(READY_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("Paper did not initialize ShamooRuntime; inspect " + log);
            }
            verifyStatusPacketPath(port, log);
        } finally {
            if (process.isAlive()) {
                process.getOutputStream().write("stop\n".getBytes(StandardCharsets.UTF_8));
                process.getOutputStream().flush();
            }
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
            output.join();
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException("Paper exited with " + process.exitValue() + "; inspect " + log);
        }
    }

    private static void verifyStatusPacketPath(int port, Path log) throws IOException, InterruptedException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            OutputStream output = socket.getOutputStream();
            java.io.ByteArrayOutputStream handshake = new java.io.ByteArrayOutputStream();
            writeVarInt(handshake, 772);
            byte[] host = "127.0.0.1".getBytes(StandardCharsets.UTF_8);
            writeVarInt(handshake, host.length);
            handshake.write(host);
            handshake.write(port >>> 8);
            handshake.write(port);
            writeVarInt(handshake, 1);
            writePacket(output, 0, handshake.toByteArray());
            writePacket(output, 0, new byte[0]);
            InputStream input = socket.getInputStream();
            try {
                readVarInt(input);
                throw new IllegalStateException("Paper status response was not cancelled by the test subscriber");
            } catch (SocketTimeoutException expected) {
                // Cancellation deliberately leaves the status request without a response.
            }
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < deadline) {
            if (Files.exists(log) && Files.readString(log).contains(
                    "SHAMOO_PACKET_SMOKE intercepted=1 action=cancel")) {
                return;
            }
            Thread.sleep(50);
        }
        throw new IllegalStateException("Paper packet subscriber metric was not observed; inspect " + log);
    }

    private static void writePacket(OutputStream output, int id, byte[] payload) throws IOException {
        java.io.ByteArrayOutputStream packet = new java.io.ByteArrayOutputStream();
        writeVarInt(packet, id);
        packet.write(payload);
        writeVarInt(output, packet.size());
        packet.writeTo(output);
        output.flush();
    }

    private static void writeVarInt(OutputStream output, int value) throws IOException {
        int remaining = value;
        do {
            int current = remaining & 0x7f;
            remaining >>>= 7;
            output.write(remaining == 0 ? current : current | 0x80);
        } while (remaining != 0);
    }

    private static int readVarInt(InputStream input) throws IOException {
        int result = 0;
        for (int shift = 0; shift < 35; shift += 7) {
            int current = input.read();
            if (current < 0) {
                throw new IOException("unexpected end of packet");
            }
            result |= (current & 0x7f) << shift;
            if ((current & 0x80) == 0) {
                return result;
            }
        }
        throw new IOException("invalid VarInt");
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
