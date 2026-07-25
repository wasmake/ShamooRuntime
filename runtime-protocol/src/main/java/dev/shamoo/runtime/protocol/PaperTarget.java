package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Enabled-discriminated Paper target requirements. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record PaperTarget(
        boolean enabled,
        SemverRange minecraft,
        SemverRange paperApi,
        boolean nms,
        boolean packets) {
    public PaperTarget {
        if (enabled) {
            ManifestValidation.required(minecraft, "/platforms/paper/minecraft");
            ManifestValidation.required(paperApi, "/platforms/paper/paperApi");
        } else if (minecraft != null || paperApi != null || nms || packets) {
            ManifestValidation.fail("invalid_disabled_target", "/platforms/paper",
                    "disabled target may contain only enabled=false");
        }
    }
}
