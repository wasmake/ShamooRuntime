package dev.shamoo.runtime.protocol;

/** Reload observation policy; it does not imply that a host implements hot reload. */
public record ReloadPolicy(boolean watch, int debounceMs, boolean preserveState) {
    public static final int MAX_DEBOUNCE_MS = 60_000;

    public ReloadPolicy {
        if (debounceMs < 0 || debounceMs > MAX_DEBOUNCE_MS) {
            ManifestValidation.fail("invalid_debounce", "/reload/debounceMs",
                    "must be between 0 and " + MAX_DEBOUNCE_MS + " milliseconds");
        }
    }

}
