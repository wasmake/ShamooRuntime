package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Pattern;
import org.semver4j.Semver;

/** A strictly parsed semantic version. */
public record SemanticVersion(@JsonValue String value) implements Version {
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

    @Override
    public int major() {
        return parsed().getMajor();
    }

    @Override
    public int minor() {
        return parsed().getMinor();
    }

    @Override
    public int patch() {
        return parsed().getPatch();
    }

    @Override
    public VersionScheme scheme() {
        return VersionScheme.SEMANTIC;
    }

    @Override
    public String original() {
        return value;
    }

    @Override
    public int compareTo(Version other) {
        if (!(other instanceof SemanticVersion semantic)) {
            throw new IllegalArgumentException("Cannot compare semantic and calendar versions");
        }
        return comparePrecedence(semantic);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof SemanticVersion semantic && value.equals(semantic.value);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value);
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
