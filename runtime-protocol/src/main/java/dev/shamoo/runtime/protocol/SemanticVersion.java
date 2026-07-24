package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.regex.Pattern;
import org.semver4j.Semver;

/** A strictly parsed semantic version. */
public record SemanticVersion(@JsonValue String value) {
    static final String PATTERN = "^(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)"
            + "(?:-(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*)"
            + "(?:\\.(?:0|[1-9][0-9]*|[0-9]*[A-Za-z-][0-9A-Za-z-]*))*)?"
            + "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)?$";
    private static final Pattern STRICT_SEMVER = Pattern.compile(PATTERN);

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public SemanticVersion {
        validate(value, "/version");
    }

    /** Parses a semantic version and reports validation against the supplied JSON pointer. */
    public static SemanticVersion parse(String value, String path) {
        validate(value, path);
        return new SemanticVersion(value);
    }

    Semver parsed() {
        return Semver.parse(value);
    }

    public int comparePrecedence(SemanticVersion other) {
        return parsed().compareTo(other.parsed());
    }

    static void validate(String value, String path) {
        ManifestValidation.text(value, path);
        if (!STRICT_SEMVER.matcher(value).matches() || Semver.parse(value) == null) {
            ManifestValidation.fail("invalid_semver", path, "is not a strict semantic version: " + value);
        }
    }
}
