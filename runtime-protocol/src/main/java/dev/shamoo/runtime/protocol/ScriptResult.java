package dev.shamoo.runtime.protocol;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/** The normalized result of script execution. */
public record ScriptResult(String requestId, Status status, String value, String error, Duration elapsed) {
    public enum Status { SUCCESS, FAILURE }

    public ScriptResult {
        if (requestId == null || requestId.isBlank()) {
            throw new IllegalArgumentException("requestId must not be blank");
        }
        status = Objects.requireNonNull(status, "status");
        elapsed = Objects.requireNonNull(elapsed, "elapsed");
        if (elapsed.isNegative()) {
            throw new IllegalArgumentException("elapsed must not be negative");
        }
        if (status == Status.SUCCESS && error != null) {
            throw new IllegalArgumentException("successful results cannot contain an error");
        }
        if (status == Status.FAILURE && (error == null || error.isBlank())) {
            throw new IllegalArgumentException("failed results require an error");
        }
    }

    public static ScriptResult success(String requestId, String value, Duration elapsed) {
        return new ScriptResult(requestId, Status.SUCCESS, value, null, elapsed);
    }

    public static ScriptResult failure(String requestId, String error, Duration elapsed) {
        return new ScriptResult(requestId, Status.FAILURE, null, error, elapsed);
    }

    public Optional<String> optionalValue() {
        return Optional.ofNullable(value);
    }

    public Optional<String> optionalError() {
        return Optional.ofNullable(error);
    }
}
