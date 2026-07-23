package dev.shamoo.runtime.core;

/** Explicit policy for moving repeated failures or resource leaks into quarantine. */
public record QuarantinePolicy(int failuresBeforeQuarantine, boolean quarantineResourceLeaks) {
    private static final int MINIMUM_FAILURES = 1;
    public static final QuarantinePolicy DEFAULT = new QuarantinePolicy(3, true);

    public QuarantinePolicy {
        if (failuresBeforeQuarantine < MINIMUM_FAILURES) {
            throw new IllegalArgumentException("failuresBeforeQuarantine must be positive");
        }
    }
}
