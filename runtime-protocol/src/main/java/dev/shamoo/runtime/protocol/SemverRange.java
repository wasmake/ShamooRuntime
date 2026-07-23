package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.regex.Pattern;
import org.semver4j.range.RangeList;
import org.semver4j.range.RangeListFactory;

/** An immutable NPM-compatible semantic-version range. */
public record SemverRange(@JsonValue String value) {
    private static final String NUMBER = "(?:0|[1-9]\\d*)";
    private static final String IDENTIFIER = "(?:0|[1-9]\\d*|\\d*[A-Za-z-][0-9A-Za-z-]*)";
    private static final String PRERELEASE = "(?:-" + IDENTIFIER + "(?:\\." + IDENTIFIER + ")*)";
    private static final String BUILD = "(?:\\+[0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*)";
    private static final String FULL_VERSION = NUMBER + "\\." + NUMBER + "\\." + NUMBER
            + PRERELEASE + "?" + BUILD + "?";
    private static final String WILDCARD = "(?:x|X|\\*)";
    private static final String PARTIAL_VERSION = "(?:" + FULL_VERSION + "|" + NUMBER
            + "(?:\\.(?:" + NUMBER + "(?:\\.(?:" + NUMBER + "|" + WILDCARD + "))?|" + WILDCARD + "))?"
            + "|" + WILDCARD + ")";
    private static final String SIMPLE = "(?:(?:<=|>=|<|>|=)[ \\t]*|(?:\\^|~>?))?" + PARTIAL_VERSION;
    private static final String SET = "(?:" + PARTIAL_VERSION + "[ \\t]+-[ \\t]+" + PARTIAL_VERSION
            + "|" + SIMPLE + "(?:[ \\t]+" + SIMPLE + ")*)";
    private static final Pattern NPM_RANGE = Pattern.compile(
            "^[ \\t]*" + SET + "(?:[ \\t]*\\|\\|[ \\t]*" + SET + ")*[ \\t]*$");

    @JsonCreator
    public SemverRange {
        validate(value, "/range");
    }

    /** Parses a semantic-version range and reports validation against the supplied JSON pointer. */
    public static SemverRange parse(String value, String path) {
        validate(value, path);
        return new SemverRange(value);
    }

    static void validate(String value, String path) {
        ManifestValidation.text(value, path);
        try {
            if (!NPM_RANGE.matcher(value).matches() || RangeListFactory.create(value).get().isEmpty()) {
                ManifestValidation.fail("invalid_semver_range", path,
                        "is not a valid semantic-version range: " + value);
            }
        } catch (RuntimeException exception) {
            ManifestValidation.fail("invalid_semver_range", path, "is not a valid semantic-version range: " + value);
        }
    }

    public boolean includes(SemanticVersion version) {
        RangeList ranges = RangeListFactory.create(value);
        return version.parsed().satisfies(ranges);
    }
}
