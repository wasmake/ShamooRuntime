package dev.shamoo.runtime.platform.paper;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestAssertionsShouldIncludeMessage", "PMD.UnitTestContainsTooManyAsserts",
        "PMD.UseProperClassLoader"})
class GeneratedPaperApiRegistryTest {
    @Test
    void indexesOnlyExactGeneratedMemberIdentities() throws IOException {
        var model = new ObjectMapper().readTree("""
                {
                  "schemaVersion": 2,
                  "platform": "paper",
                  "declarations": [{
                    "javaName": "java.lang.String",
                    "constructors": [{
                      "id": "java.lang.String#<init>()V",
                      "descriptor": "()V",
                      "parameters": []
                    }],
                    "methods": [{
                      "id": "java.lang.String#length()I",
                      "name": "length",
                      "descriptor": "()I",
                      "parameters": [],
                      "returns": "int"
                    }],
                    "fields": []
                  }]
                }
                """);

        GeneratedPaperApiRegistry registry = GeneratedPaperApiRegistry.parse(
                getClass().getClassLoader(), model);

        assertEquals(2, registry.memberCount());
        assertEquals("java.lang.String#length()I", registry.require("java.lang.String",
                GeneratedPaperApiRegistry.Kind.METHOD, "length", "()I").id());
        assertEquals(String.class, registry.requireType("java.lang.String"));
        assertThrows(IllegalArgumentException.class, () -> registry.require("java.lang.String",
                GeneratedPaperApiRegistry.Kind.METHOD, "substring", "(I)Ljava/lang/String;"));
    }

    @Test
    void rejectsModelsWithoutJvmDescriptors() throws IOException {
        var model = new ObjectMapper().readTree("""
                {
                  "schemaVersion": 2,
                  "platform": "paper",
                  "declarations": [{
                    "javaName": "java.lang.String",
                    "methods": [{"name": "length", "parameters": [], "returns": "int"}]
                  }]
                }
                """);

        assertThrows(IOException.class, () -> GeneratedPaperApiRegistry.parse(
                getClass().getClassLoader(), model));
    }
}
