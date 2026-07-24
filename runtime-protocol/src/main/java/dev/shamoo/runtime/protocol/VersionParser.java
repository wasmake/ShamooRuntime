package dev.shamoo.runtime.protocol;

/** Parses version strings using strict, scheme-specific validators. */
public final class VersionParser {
    private VersionParser() {
    }

    /**
     * Detects a version scheme, preferring calendar versions for ambiguous positive-major numeric values.
     * Strict semantic domains should call {@link #parseSemantic(String)} instead.
     */
    public static Version parse(String value) {
        return parse(value, "/version");
    }

    /** Detects a version scheme and reports validation against the supplied JSON pointer. */
    public static Version parse(String value, String path) {
        ManifestValidation.text(value, path);
        if (CalendarVersion.isValid(value)) {
            return parseCalendar(value, path);
        }
        if (SemanticVersion.isValid(value)) {
            return parseSemantic(value, path);
        }
        ManifestValidation.fail("invalid_version", path,
                "is neither a strict calendar nor semantic version: " + value);
        throw new IllegalStateException("Unreachable validation state");
    }

    /** Parses a strict SemVer 2.0 value. */
    public static SemanticVersion parseSemantic(String value) {
        return parseSemantic(value, "/version");
    }

    /** Parses a strict SemVer 2.0 value against the supplied JSON pointer. */
    public static SemanticVersion parseSemantic(String value, String path) {
        return SemanticVersion.parse(value, path);
    }

    /** Parses a strict calendar or Minecraft version. */
    public static CalendarVersion parseCalendar(String value) {
        return parseCalendar(value, "/version");
    }

    /** Parses a strict calendar or Minecraft version against the supplied JSON pointer. */
    public static CalendarVersion parseCalendar(String value, String path) {
        return CalendarVersion.parse(value, path);
    }
}
