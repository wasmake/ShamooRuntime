package dev.shamoo.runtime.protocol;

import java.util.Objects;

/** Strict Paper and Velocity target declarations; at least one must be enabled. */
public record PlatformTargets(PaperTarget paper, VelocityTarget velocity) {
    public PlatformTargets {
        paper = Objects.requireNonNull(paper, "paper");
        velocity = Objects.requireNonNull(velocity, "velocity");
        if (!paper.enabled() && !velocity.enabled()) {
            ManifestValidation.fail("missing_platform", "/platforms", "at least one platform must be enabled");
        }
    }
}
