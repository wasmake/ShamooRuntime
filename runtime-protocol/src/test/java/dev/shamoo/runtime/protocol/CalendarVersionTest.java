package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings({
        "PMD.AvoidUsingHardCodedIP",
        "PMD.UnitTestContainsTooManyAsserts",
        "PMD.UnitTestAssertionsShouldIncludeMessage"
})
class CalendarVersionTest {
    @Test
    void normalizesAnOmittedPatchWhilePreservingOriginalValue() {
        CalendarVersion abbreviated = new CalendarVersion("26.2");
        CalendarVersion explicit = new CalendarVersion("26.2.0");

        assertEquals(26, abbreviated.major());
        assertEquals(2, abbreviated.minor());
        assertEquals(0, abbreviated.patch());
        assertEquals(VersionScheme.CALENDAR, abbreviated.scheme());
        assertEquals("26.2", abbreviated.original());
        assertEquals(abbreviated, explicit);
        assertEquals(abbreviated.hashCode(), explicit.hashCode());
    }

    @Test
    void comparesAllNumericComponents() {
        assertTrue(new CalendarVersion("1.21.8").compareTo(new CalendarVersion("1.21.7")) > 0);
        assertTrue(new CalendarVersion("1.22").compareTo(new CalendarVersion("1.21.99")) > 0);
        assertTrue(new CalendarVersion("26.1").compareTo(new CalendarVersion("25.12.99")) > 0);
    }

    @Test
    void rejectsInvalidCalendarVersions() {
        List<String> values = List.of("0.1.0", "26", "26.02", "26.2.0.1", "26.2-rc.1", "v26.2");
        for (String value : values) {
            ManifestValidationException exception = assertThrows(
                    ManifestValidationException.class, () -> new CalendarVersion(value), value);
            assertEquals("invalid_calendar_version", exception.diagnostics().getFirst().code(), value);
        }
    }

    @Test
    void rejectsMixedSchemeComparison() {
        CalendarVersion calendar = new CalendarVersion("1.21.8");

        assertThrows(IllegalArgumentException.class, () -> calendar.compareTo(new SemanticVersion("1.21.8")));
    }
}
