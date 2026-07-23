# ADR 0002: Pin Toolchain and External APIs

- Status: Accepted
- Date: 2026-07-23

## Context

Mutable snapshots make server plugin builds non-reproducible. Paper and Velocity publish supported API lines through
snapshot repositories even when a release coordinate is unavailable.

## Decision

Use Java 21 and centralize versions in `gradle/libs.versions.toml`. Resolve Paper and Velocity through their immutable
timestamped snapshot versions. Pin Javet and swc4j release versions. Fail builds on dependency version conflicts and
compiler warnings.

## Consequences

Build inputs remain stable until deliberately upgraded. Platform upgrades require selecting and validating a new
timestamped artifact rather than silently consuming the latest snapshot.
