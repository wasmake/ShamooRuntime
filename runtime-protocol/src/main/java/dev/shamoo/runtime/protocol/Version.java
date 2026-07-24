package dev.shamoo.runtime.protocol;

/** A strictly parsed version with scheme-specific comparison semantics. */
public sealed interface Version extends Comparable<Version> permits SemanticVersion, CalendarVersion {
    int major();

    int minor();

    int patch();

    VersionScheme scheme();

    String original();
}
