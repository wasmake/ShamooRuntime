package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Enabled-discriminated Velocity target requirements. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VelocityTarget(boolean enabled, SemverRange velocityApi) {
    public VelocityTarget {
        if (enabled) {
            ManifestValidation.required(velocityApi, "/platforms/velocity/velocityApi");
        } else if (velocityApi != null) {
            ManifestValidation.fail("invalid_disabled_target", "/platforms/velocity",
                    "disabled target may contain only enabled=false");
        }
    }
}
