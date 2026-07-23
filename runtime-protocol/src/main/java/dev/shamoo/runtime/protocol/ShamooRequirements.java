package dev.shamoo.runtime.protocol;

import java.util.Objects;

/** Shamoo API, runtime, and manifest protocol accepted by a plugin. */
public record ShamooRequirements(SemverRange api, SemverRange runtime, int manifest) {
    public static final int CURRENT_MANIFEST_VERSION = 1;

    public ShamooRequirements {
        api = Objects.requireNonNull(api, "api");
        runtime = Objects.requireNonNull(runtime, "runtime");
        if (manifest != CURRENT_MANIFEST_VERSION) {
            ManifestValidation.fail("unsupported_manifest_version", "/shamoo/manifest",
                    "supported manifest version is " + CURRENT_MANIFEST_VERSION + ", received " + manifest);
        }
    }
}
