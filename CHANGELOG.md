# Changelog

All notable changes will be documented here. This project follows Keep a Changelog and intends to use Semantic
Versioning after its first release.

## Unreleased

### Added

- Phase 7/8 ASM artifact scanning, deterministic bridge descriptors, generated event registries, explicit exclusions,
  and 100% supported-surface coverage reports for pinned Paper, Velocity, and their Adventure API versions.
- Owned Paper live-event, command-map, Folia scheduler, plugin messaging, and Adventure-compatible adapters.
- Velocity continuation-aware live events, Brigadier/simple/raw commands, asynchronous scheduling, messaging, backend
  routing, and Adventure-compatible adapters.
- Exact-version Paper NMS packet descriptor generation and permission-gated Netty interception with opaque live packet
  handles, cancellation/replacement, bounded dispatch, event-loop safety, and deterministic cleanup.
- Phase 6 secure plugin discovery with stable-file checks, strict descriptors, SHA-256 inventories, path defenses,
  immutable installed candidates, and a staging boundary.
- Central thread-safe plugin lifecycle state machine, correlated transition history, structured phase failures,
  serialized timed hooks, invocation admission and draining, explicit retries, and quarantine policy.
- Deterministic required/optional semver dependency graph with ordering hints, canonical cycle paths, block reasons,
  compatibility reevaluation, dependency-first enable, and dependent-first disable.
- Typed plugin-owned resource registrations, reverse cleanup, aggregated cleanup failures, retryable leak reports, and
  immutable lifecycle, dependency, resource, invocation, and metrics introspection snapshots.
- Engine-neutral lifecycle factory and hook interfaces with a `runtime-javet` manager adapter.
- Phase 5 one-Node-runtime-per-plugin manager with isolated globals and module caches.
- Dedicated owner-thread confinement, bounded invocation backpressure, measured metrics, and deterministic shutdown.
- Controlled ESM/CommonJS virtual modules, promise and Node event-loop driving, direct allow-listed host callbacks,
  structured source-mapped errors, and asynchronous error reporting.
- Canonical `NodePolicy` permissions with mediated safe builtins and plugin-root filesystem operations; unsupported
  native filesystem, network, worker, child-process, and addon paths are explicitly denied.
- Immutable plugin manifest v1 models for identity, enabled platform targets, dependencies, Node policy, and reload.
- Strict Jackson manifest codec, canonical JSON Schema, structured protocol diagnostics, and golden round-trip tests.
- Semver4j-backed runtime, API, Minecraft, Paper API, Velocity API, and capability compatibility negotiation.
- Phase 1 Java 21 multi-module runtime foundation.
- Protocol, host, runtime, code-generation metadata, and platform adapter APIs.
- Separate Paper and Velocity bootstrap artifacts and integration probes.
- Reproducible Gradle configuration, quality checks, CI, and project governance files.
