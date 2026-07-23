package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Enabled-discriminated Paper target requirements. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaperTarget(boolean enabled, String entrypoint, SemverRange minecraft, SemverRange paperApi) {
    public PaperTarget {
        if (enabled) {
            ManifestValidation.required(entrypoint, "/platforms/paper/entrypoint");
            ManifestValidation.required(minecraft, "/platforms/paper/minecraft");
            ManifestValidation.required(paperApi, "/platforms/paper/paperApi");
        }
        if (entrypoint != null) {
            entrypoint = ManifestValidation.entrypoint(entrypoint, "/platforms/paper/entrypoint");
        }
    }
}
