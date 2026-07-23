package dev.shamoo.runtime.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import dev.shamoo.runtime.codegen.ApiDescriptorWriter.PacketInfo;
import dev.shamoo.runtime.codegen.ApiDescriptorWriter.PacketRegistration;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({
    "PMD.AvoidDuplicateLiterals",
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UnitTestAssertionsShouldIncludeMessage"
})
class ApiDescriptorWriterTest {
    private static final Set<String> ROOT_PROPERTIES = Set.of(
            "schemaVersion", "platform", "apiVersion", "mapping", "generatedBy",
            "declarations", "events", "packets");
    @TempDir
    Path temporaryDirectory;

    @Test
    void emitsCanonicalVersionedModelDeterministically() throws IOException {
        Path artifact = fixtureJar();
        ApiModel model = new AsmApiScanner().scan("paper", List.of(artifact), name -> true);
        ApiDescriptorWriter writer = new ApiDescriptorWriter();
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        writer.write(model, "1.21.8-test", "fixture", first, List.of());
        writer.write(model, "1.21.8-test", "fixture", second, List.of());

        String json = Files.readString(first.resolve("model.json"));
        assertEquals(json, Files.readString(second.resolve("model.json")));
        JsonNode root = new ObjectMapper().readTree(json);
        assertEquals(ApiDescriptorWriter.SCHEMA_VERSION, root.required("schemaVersion").intValue());
        assertEquals(ROOT_PROPERTIES, fieldNames(root));
        assertTrue(root.required("declarations").isArray());
        assertTrue(root.required("events").isArray());
        assertTrue(root.required("packets").isArray());
        JsonNode declaration = root.required("declarations").get(0);
        assertTrue(declaration.has("constructors"));
        assertTrue(declaration.has("methods"));
        assertFalse(declaration.has("signature"));
    }

    @Test
    void emitsStableDeduplicatedPacketRegistrations() throws IOException {
        ApiModel model = new AsmApiScanner().scan("paper-packets", List.of(fixtureJar()), name -> true);
        PacketRegistration play = new PacketRegistration("play", "clientbound", 3);
        PacketRegistration configuration = new PacketRegistration("configuration", "clientbound", 1);
        new ApiDescriptorWriter().write(model, "1.21.8-test", "fixture", temporaryDirectory.resolve("packets"),
                List.of(), List.of(new PacketInfo("Fixture", "example.Fixture",
                        List.of(play, configuration, play))));

        JsonNode packet = new ObjectMapper().readTree(
                temporaryDirectory.resolve("packets/model.json").toFile()).required("packets").get(0);
        assertEquals(Set.of("type", "javaName", "registrations"), fieldNames(packet));
        assertEquals(2, packet.required("registrations").size());
        assertEquals("configuration", packet.required("registrations").get(0).required("phase").textValue());
        assertEquals(1, packet.required("registrations").get(0).required("id").intValue());
    }

    @Test
    void failsWhenScannerInventoryCannotBeEmittedCompletely() throws IOException {
        ApiModel scanned = new AsmApiScanner().scan("paper", List.of(fixtureJar()), name -> true);
        ApiModel omitted = new ApiModel(scanned.platform(), scanned.types(),
                new ApiModel.Inventory(scanned.inventory().eligibleTypes() + 1,
                        scanned.inventory().eligibleMembers(), scanned.inventory().eligibleEvents(),
                        scanned.inventory().eligibleExceptions()));

        assertThrows(IOException.class, () -> new ApiDescriptorWriter().write(omitted, "test", "fixture",
                temporaryDirectory.resolve("omitted"), List.of()));
    }

    private Path fixtureJar() throws IOException {
        Path artifact = temporaryDirectory.resolve("fixture.jar");
        String resource = AsmApiScannerTest.Fixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact));
                var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            output.putNextEntry(new JarEntry(resource));
            output.write(input.readAllBytes());
            output.closeEntry();
        }
        return artifact;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> result = new java.util.HashSet<>();
        node.fieldNames().forEachRemaining(result::add);
        return result;
    }
}
