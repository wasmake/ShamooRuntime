package dev.shamoo.runtime.protocol;

import java.io.Serializable;
import java.util.List;
import java.util.Objects;

/** Complete compatibility outcome; all discovered reasons are retained in stable order. */
public record CompatibilityResult(boolean compatible, List<IncompatibilityReason> reasons) implements Serializable {
    private static final long serialVersionUID = 1L;

    public CompatibilityResult {
        reasons = List.copyOf(Objects.requireNonNull(reasons, "reasons"));
        if (compatible == !reasons.isEmpty()) {
            throw new IllegalArgumentException("compatible must be true exactly when reasons is empty");
        }
    }
}
