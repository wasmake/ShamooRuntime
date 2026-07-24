package dev.shamoo.runtime.protocol;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A strictly parsed calendar or Minecraft version with an optional patch component. */
public final class CalendarVersion implements Version {
    private static final Pattern CALENDAR_VERSION = Pattern.compile(
            "^([1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:\\.(0|[1-9][0-9]*))?$");

    private final int majorValue;
    private final int minorValue;
    private final int patchValue;
    private final String sourceValue;

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public CalendarVersion(String value) {
        this(value, "/version");
    }

    private CalendarVersion(String value, String path) {
        ManifestValidation.text(value, path);
        Matcher matcher = CALENDAR_VERSION.matcher(value);
        if (!matcher.matches()) {
            ManifestValidation.fail("invalid_calendar_version", path,
                    "is not a strict calendar version: " + value);
        }
        try {
            majorValue = Integer.parseInt(matcher.group(1));
            minorValue = Integer.parseInt(matcher.group(2));
            patchValue = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        } catch (NumberFormatException exception) {
            ManifestValidation.fail("invalid_calendar_version", path,
                    "contains a component outside the supported integer range: " + value);
            throw new IllegalStateException("Unreachable validation state", exception);
        }
        sourceValue = value;
    }

    /** Parses a calendar version and reports validation against the supplied JSON pointer. */
    public static CalendarVersion parse(String value, String path) {
        return new CalendarVersion(value, path);
    }

    @Override
    public int major() {
        return majorValue;
    }

    @Override
    public int minor() {
        return minorValue;
    }

    @Override
    public int patch() {
        return patchValue;
    }

    @Override
    public VersionScheme scheme() {
        return VersionScheme.CALENDAR;
    }

    @Override
    @JsonValue
    public String original() {
        return sourceValue;
    }

    @Override
    public int compareTo(Version other) {
        if (!(Objects.requireNonNull(other, "other") instanceof CalendarVersion calendar)) {
            throw new IllegalArgumentException("Cannot compare calendar and semantic versions");
        }
        int majorComparison = Integer.compare(majorValue, calendar.majorValue);
        if (majorComparison != 0) {
            return majorComparison;
        }
        int minorComparison = Integer.compare(minorValue, calendar.minorValue);
        return minorComparison != 0 ? minorComparison : Integer.compare(patchValue, calendar.patchValue);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof CalendarVersion calendar
                && majorValue == calendar.majorValue
                && minorValue == calendar.minorValue
                && patchValue == calendar.patchValue;
    }

    @Override
    public int hashCode() {
        return Objects.hash(majorValue, minorValue, patchValue);
    }

    @Override
    public String toString() {
        return sourceValue;
    }
}
