package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class VersionParserTest {
    @Test
    void genericDetectionPrefersCalendarForPositiveNumericVersions() {
        assertInstanceOf(CalendarVersion.class, VersionParser.parse("1.21.8"));
        assertInstanceOf(CalendarVersion.class, VersionParser.parse("26.2"));
        assertInstanceOf(SemanticVersion.class, VersionParser.parse("0.1.0"));
        assertInstanceOf(SemanticVersion.class, VersionParser.parse("1.2.3-rc.1"));
    }

    @Test
    void explicitParsersDoNotSwitchSchemes() {
        assertInstanceOf(SemanticVersion.class, VersionParser.parseSemantic("1.21.8"));
        assertThrows(ManifestValidationException.class, () -> VersionParser.parseCalendar("0.1.0"));
        assertThrows(ManifestValidationException.class, () -> VersionParser.parseSemantic("26.2"));
    }

    @Test
    void genericFailureUsesItsOwnDiagnosticAndRequestedPath() {
        ManifestValidationException exception = assertThrows(
                ManifestValidationException.class, () -> VersionParser.parse("not-a-version", "/paper/api"));

        assertEquals("invalid_version", exception.diagnostics().getFirst().code());
        assertEquals("/paper/api", exception.diagnostics().getFirst().path());
    }
}
