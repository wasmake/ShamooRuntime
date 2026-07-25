package dev.shamoo.runtime.javet;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.shamoo.runtime.core.SourcePosition;
import dev.shamoo.runtime.protocol.PluginArtifactProtocol;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/** Minimal source-map v3 loader for generated-to-original stack locations. */
@SuppressWarnings({"PMD.UseVarargs", "PMD.AssignmentInOperand", "PMD.AvoidLiteralsInIfCondition"})
final class SourceMapV3 {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final String BASE64 = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789+/";

    private SourceMapV3() {
    }

    static ValidatedSourceMap validateAdjacent(Path root) {
        return new ValidatedSourceMap(parseAdjacent(root));
    }

    static CompletionStage<Void> registerAdjacent(ShamooNodeRuntime runtime, Path root) {
        try {
            return validateAdjacent(root).register(runtime);
        } catch (RuntimeException exception) {
            return CompletableFuture.failedFuture(exception);
        }
    }

    private static List<Mapping> parseAdjacent(Path root) {
        String entrypoint = PluginArtifactProtocol.MODULE_FILE;
        Path map = root.resolve(PluginArtifactProtocol.SOURCE_MAP_FILE).normalize();
        if (!map.startsWith(root) || !Files.exists(map)) {
            throw new IllegalArgumentException("source map is missing: " + PluginArtifactProtocol.SOURCE_MAP_FILE);
        }
        if (!Files.isRegularFile(map)) {
            throw new IllegalArgumentException("source map is not a regular file");
        }
        try {
            JsonNode value = MAPPER.readTree(Files.readString(map, StandardCharsets.UTF_8));
            if (!value.isObject() || !value.path("version").isIntegralNumber()
                    || value.path("version").intValue() != 3
                    || !value.path("sources").isArray() || !value.path("mappings").isTextual()) {
                throw new IllegalArgumentException("adjacent source map must be source-map v3");
            }
            String sourceRoot = value.path("sourceRoot").isTextual() ? value.path("sourceRoot").textValue() : "";
            List<String> sources = new ArrayList<>();
            for (JsonNode source : value.path("sources")) {
                if (!source.isTextual() || source.textValue().isBlank()) {
                    throw new IllegalArgumentException("source map sources must be non-blank strings");
                }
                sources.add(normalizeSource(Path.of(entrypoint).getParent(), sourceRoot, source.textValue()));
            }
            return decode(entrypoint, value.path("mappings").textValue(), sources);
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("adjacent source map is malformed", exception);
        } catch (IOException exception) {
            throw new IllegalArgumentException("unable to read adjacent source map", exception);
        }
    }

    private static List<Mapping> decode(String generated, String mappings, List<String> sources) {
        List<Mapping> result = new ArrayList<>();
        int sourceIndex = 0;
        int sourceLine = 0;
        int sourceColumn = 0;
        String[] lines = mappings.split(";", -1);
        for (int generatedLine = 0; generatedLine < lines.length; generatedLine++) {
            int generatedColumn = 0;
            if (lines[generatedLine].isEmpty()) {
                continue;
            }
            for (String segment : lines[generatedLine].split(",", -1)) {
                if (segment.isEmpty()) {
                    continue;
                }
                int[] cursor = {0};
                generatedColumn += vlq(segment, cursor);
                if (generatedColumn < 0) {
                    throw new IllegalArgumentException("source map generated column is out of range");
                }
                if (cursor[0] == segment.length()) {
                    continue;
                }
                sourceIndex += vlq(segment, cursor);
                sourceLine += vlq(segment, cursor);
                sourceColumn += vlq(segment, cursor);
                if (sourceIndex < 0 || sourceIndex >= sources.size() || sourceLine < 0 || sourceColumn < 0) {
                    throw new IllegalArgumentException("source map mapping is out of range");
                }
                result.add(new Mapping(
                        new SourcePosition(generated, generatedLine + 1, generatedColumn + 1),
                        new SourcePosition(sources.get(sourceIndex), sourceLine + 1, sourceColumn + 1)));
                if (cursor[0] < segment.length()) {
                    vlq(segment, cursor); // Optional name field.
                }
                if (cursor[0] != segment.length()) {
                    throw new IllegalArgumentException("invalid source map segment");
                }
            }
        }
        return List.copyOf(result);
    }

    private static int vlq(String value, int[] cursor) {
        int result = 0;
        int shift = 0;
        boolean continuation;
        do {
            if (cursor[0] >= value.length()) {
                throw new IllegalArgumentException("truncated source map VLQ");
            }
            int digit = BASE64.indexOf(value.charAt(cursor[0]++));
            if (digit < 0) {
                throw new IllegalArgumentException("invalid source map base64 digit");
            }
            continuation = (digit & 32) != 0;
            result |= (digit & 31) << shift;
            shift += 5;
            if (shift > 30) {
                throw new IllegalArgumentException("source map VLQ is too large");
            }
        } while (continuation);
        boolean negative = (result & 1) != 0;
        int decoded = result >>> 1;
        return negative ? -decoded : decoded;
    }

    private static String normalizeSource(Path generatedParent, String root, String source) {
        Path base = generatedParent == null ? Path.of("") : generatedParent;
        if (!root.isBlank()) {
            base = base.resolve(root.replace('\\', '/'));
        }
        return base.resolve(source.replace('\\', '/')).normalize().toString().replace('\\', '/');
    }

    private record Mapping(SourcePosition generated, SourcePosition original) {
    }

    static final class ValidatedSourceMap {
        private final List<Mapping> mappings;

        private ValidatedSourceMap(List<Mapping> mappings) {
            this.mappings = List.copyOf(mappings);
        }

        CompletionStage<Void> register(ShamooNodeRuntime runtime) {
            List<CompletableFuture<Void>> registrations = mappings.stream()
                    .map(mapping -> runtime.registerSourceMap(mapping.generated(), mapping.original())).toList();
            return CompletableFuture.allOf(registrations.toArray(CompletableFuture[]::new));
        }
    }
}
