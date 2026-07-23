package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;

class SemverRangeTest {
    @Test
    void acceptsDocumentedNpmRangeForms() {
        List<String> ranges = List.of(
                "^1.0.0",
                ">=1.0.0 <2.0.0",
                ">= 1.0.0 < 2.0.0",
                "1.21.x",
                "3.x",
                "*",
                "~1.2.3",
                "~>1.2",
                "1.2.3 - 2.3.4",
                "1.2",
                "1.2.3-alpha.1+build.5",
                "^1.0.0 || ~2.1.0",
                "  >=1.0.0\t<2.0.0  ");

        for (String value : ranges) {
            assertDoesNotThrow(() -> new SemverRange(value), value);
        }
    }

    @Test
    @SuppressWarnings("PMD.UnitTestContainsTooManyAsserts")
    void rejectsTokensThatSemver4jWouldDiscardAndMalformedRanges() {
        List<String> malformedRanges = List.of(
                "latest",
                ">=1.0.0 garbage",
                "garbage >=1.0.0",
                ">=1.0.0 garbage <2.0.0",
                "1.0.0latest",
                "1.0.0 || latest",
                "|| 1.0.0",
                "1.0.0 ||",
                "1.0.0 || || 2.0.0",
                ">=",
                "^",
                "1.2.3 2.nope",
                "1.2.3, <2.0.0",
                "[1.0,2.0)",
                "v1.2.3",
                "01.2.3",
                "1.02.x",
                "1.x.3",
                "1.2.3-01",
                "1.2.3+bad_metadata",
                "1.2.3 - garbage");

        for (String value : malformedRanges) {
            ManifestValidationException exception = assertThrows(
                    ManifestValidationException.class, () -> new SemverRange(value), value);
            assertEquals("invalid_semver_range", exception.diagnostics().getFirst().code(), value);
        }

        for (String value : List.of("", "   ")) {
            ManifestValidationException exception = assertThrows(
                    ManifestValidationException.class, () -> new SemverRange(value), value);
            assertEquals("invalid_value", exception.diagnostics().getFirst().code(), value);
        }
    }
}
