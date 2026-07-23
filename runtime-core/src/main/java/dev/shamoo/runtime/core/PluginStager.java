package dev.shamoo.runtime.core;

import java.io.IOException;
import java.nio.file.Path;

/** Prepares an installation candidate without replacing the currently installed candidate. */
@FunctionalInterface
public interface PluginStager {
    InstalledPluginCandidate stage(Path source, Path stagingRoot) throws IOException;
}
