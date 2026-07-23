package dev.shamoo.runtime.core;

import dev.shamoo.runtime.protocol.ManifestCodec;
import java.nio.file.Path;
import java.util.Map;

@SuppressWarnings("PMD.TestClassWithoutTestCases")
final class TestCandidates {
    private TestCandidates() {
    }

    static InstalledPluginCandidate candidate(String id, String version, String dependencies) {
        String json = ("{\"name\":\"%s\",\"displayName\":\"%s\",\"version\":\"%s\","
                + "\"shamoo\":{\"api\":\"*\",\"runtime\":\"*\",\"manifest\":1},"
                + "\"platforms\":{\"paper\":{\"enabled\":true,\"entrypoint\":\"index.mjs\","
                + "\"minecraft\":\"*\",\"paperApi\":\"*\"},\"velocity\":{\"enabled\":false}},"
                + "\"dependencies\":%s,\"node\":{\"builtins\":[],"
                + "\"filesystem\":{\"read\":[],\"write\":[]},\"network\":false,\"workers\":false,"
                + "\"childProcess\":false,\"nativeAddons\":false},"
                + "\"reload\":{\"watch\":false,\"debounceMs\":100,\"preserveState\":false}}")
                .formatted(id, id, version, dependencies);
        return new InstalledPluginCandidate(new PluginId(id), new ManifestCodec().parse(json),
                Path.of("build/test-plugins", id), Map.of("index.mjs", id));
    }

    static InstalledPluginCandidate candidate(String id) {
        return candidate(id, "1.0.0", emptyDependencies());
    }

    static String emptyDependencies() {
        return "{\"required\":{},\"optional\":{},\"loadBefore\":[],\"loadAfter\":[]}";
    }
}
