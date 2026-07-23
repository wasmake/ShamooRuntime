# Changelog

All notable changes will be documented here. This project follows Keep a Changelog and intends to use Semantic
Versioning after its first release.

## Unreleased

### Added

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
