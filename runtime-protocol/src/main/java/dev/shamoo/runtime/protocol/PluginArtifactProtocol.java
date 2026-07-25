package dev.shamoo.runtime.protocol;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/** Canonical names for the complete Shamoo plugin artifact. */
public final class PluginArtifactProtocol {
    public static final String MODULE_FILE = "index.js";
    public static final String SOURCE_MAP_FILE = "index.js.map";
    public static final String MANIFEST_FILE = "shamoo-plugin.json";
    public static final Set<String> REQUIRED_FILES = Collections.unmodifiableSet(
            new LinkedHashSet<>(List.of(MODULE_FILE, SOURCE_MAP_FILE, MANIFEST_FILE)));

    private PluginArtifactProtocol() {
    }
}
