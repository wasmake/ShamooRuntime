package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonInclude;

/** Enabled-discriminated Velocity target requirements. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record VelocityTarget(boolean enabled, String entrypoint, SemverRange velocityApi) {
    public VelocityTarget {
        if (enabled) {
            ManifestValidation.required(entrypoint, "/platforms/velocity/entrypoint");
            ManifestValidation.required(velocityApi, "/platforms/velocity/velocityApi");
        }
        if (entrypoint != null) {
            entrypoint = ManifestValidation.entrypoint(entrypoint, "/platforms/velocity/entrypoint");
        }
    }
}
