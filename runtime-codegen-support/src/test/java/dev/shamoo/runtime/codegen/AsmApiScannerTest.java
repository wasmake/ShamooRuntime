package dev.shamoo.runtime.codegen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

@SuppressWarnings({
    "PMD.UnitTestContainsTooManyAsserts",
    "PMD.UnitTestAssertionsShouldIncludeMessage"
})
class AsmApiScannerTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void scansSignaturesRecordsVarargsAndExceptionsDeterministically() throws IOException {
        Path artifact = temporaryDirectory.resolve("fixture.jar");
        String resource = Fixture.class.getName().replace('.', '/') + ".class";
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact));
                var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            output.putNextEntry(new JarEntry(resource));
            output.write(input.readAllBytes());
            output.closeEntry();
        }
        AsmApiScanner scanner = new AsmApiScanner();
        ApiModel first = scanner.scan("test", List.of(artifact), name -> true);
        ApiModel second = scanner.scan("test", List.of(artifact), name -> true);
        assertEquals(first, second);
        var type = first.types().getFirst();
        assertTrue(type.record());
        assertTrue(type.methods().stream().anyMatch(method -> method.varargs()
                && method.exceptions().contains("java/io/IOException")));
    }

    @Test
    void resolvesInheritedFunctionalMethodsAndExcludesCompilerBridges() throws IOException {
        Path artifact = temporaryDirectory.resolve("interfaces.jar");
        try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(artifact))) {
            addClass(output, ParentFunction.class);
            addClass(output, ChildFunction.class);
            addClass(output, StringFunction.class);
        }
        ApiModel model = new AsmApiScanner().scan("test", List.of(artifact), name -> true);
        var child = model.types().stream().filter(type -> ChildFunction.class.getName().equals(type.name()))
                .findFirst().orElseThrow();
        var implementation = model.types().stream().filter(type -> StringFunction.class.getName().equals(type.name()))
                .findFirst().orElseThrow();

        assertTrue(child.functionalInterface());
        assertEquals(1, implementation.methods().stream().filter(method -> "apply".equals(method.name())).count());
    }

    private static void addClass(JarOutputStream output, Class<?> type) throws IOException {
        String resource = type.getName().replace('.', '/') + ".class";
        try (var input = Thread.currentThread().getContextClassLoader().getResourceAsStream(resource)) {
            output.putNextEntry(new JarEntry(resource));
            output.write(input.readAllBytes());
            output.closeEntry();
        }
    }

    public record Fixture<T>(T value) {
        public final void invoke(String... values) throws IOException {
            if (values.length == 0) {
                throw new IOException("empty");
            }
        }
    }

    @FunctionalInterface
    public interface ParentFunction<T> {
        T apply(T value);
    }

    @FunctionalInterface
    public interface ChildFunction extends ParentFunction<String> {
    }

    public static final class StringFunction implements ParentFunction<String> {
        @Override
        public String apply(String value) {
            return value;
        }
    }
}
