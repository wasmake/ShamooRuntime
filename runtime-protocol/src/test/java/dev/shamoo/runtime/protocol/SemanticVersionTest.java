package dev.shamoo.runtime.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.UnitTestContainsTooManyAsserts", "PMD.UnitTestAssertionsShouldIncludeMessage"})
class SemanticVersionTest {
    @Test
    void exposesStrictSemanticComponentsAndOriginalValue() {
        SemanticVersion version = new SemanticVersion("1.2.3-alpha.1+build.5");

        assertEquals(1, version.major());
        assertEquals(2, version.minor());
        assertEquals(3, version.patch());
        assertEquals(VersionScheme.SEMANTIC, version.scheme());
        assertEquals("1.2.3-alpha.1+build.5", version.original());
        assertEquals(version.original(), version.value());
    }

    @Test
    void enforcesSemverPrecedenceWithoutChangingValueEquality() {
        SemanticVersion prerelease = new SemanticVersion("1.0.0-rc.1");
        SemanticVersion release = new SemanticVersion("1.0.0");
        SemanticVersion firstBuild = new SemanticVersion("1.0.0+first");
        SemanticVersion secondBuild = new SemanticVersion("1.0.0+second");

        assertTrue(prerelease.compareTo(release) < 0);
        assertEquals(0, firstBuild.comparePrecedence(secondBuild));
        assertNotEquals(firstBuild, secondBuild);
    }

    @Test
    void rejectsNonStrictSemanticVersions() {
        for (String value : List.of("1.2", "v1.2.3", "01.2.3", "1.2.3-01", "1.2.3+bad_metadata")) {
            ManifestValidationException exception = assertThrows(
                    ManifestValidationException.class, () -> new SemanticVersion(value), value);
            assertEquals("invalid_semver", exception.diagnostics().getFirst().code(), value);
        }
    }

    @Test
    void rejectsMixedSchemeComparison() {
        SemanticVersion semantic = new SemanticVersion("1.21.8");

        assertThrows(IllegalArgumentException.class, () -> semantic.compareTo(new CalendarVersion("1.21.8")));
    }
}
