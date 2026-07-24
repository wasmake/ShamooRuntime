# ADR 0004: Core-owned Plugin Lifecycle and Resources

- Status: Accepted
- Date: 2026-07-23

## Context

Plugin discovery, dependency ordering, hook execution, active invocation draining, and host resource cleanup must have
one deterministic owner. Putting those decisions in Paper, Velocity, or Javet would duplicate behavior and expose
implementation-specific objects across module boundaries. Updating an installed plugin also needs immutable staging,
generation-specific resource ownership, and rollback that does not disturb the active runtime when candidate
preparation fails.

## Decision

Keep lifecycle policy in `runtime-core`. Use one centrally defined, thread-safe state machine with immutable correlated
history. Discover stable directory candidates with strict descriptors, path defenses, and checksum inventories.
Resolve required and optional semver dependencies plus ordering hints into deterministic enable and reverse-disable
orders, explicit block reasons, and complete cycle paths.

Serialize each plugin's hooks through core-owned runtime interfaces, apply bounded hook and drain waits, and admit
invocations only while ready. Register host and engine resources by plugin and category, clean them in reverse order,
retain failed closes as observable leaks, and apply an explicit quarantine policy. Expose immutable administrative
snapshots. Javet is connected by an adapter in `runtime-javet`; no Javet type enters the core contract.

For Phase 9, observe directories through a recursive quiet-window watcher, then retain discovery's independent stable
snapshot validation. Prepare replacement runtimes behind closed invocation admission and isolated resource ownership.
Reject graph changes that block an active dependent. If requested and supported by both generations, transfer opaque
byte state before draining. Stop old admission, drain leases, disable, atomically publish the prepared generation and
new graph, then unload and clean the retired generation. Failures before publication discard only the candidate;
cleanup failures after publication remain visible as retired leaks and do not roll back the active new generation.
Persisted update journals and crash recovery remain deferred.

## Consequences

Paper and Velocity can share identical lifecycle behavior, dependency order is reproducible, and failures retain an
auditable phase and correlation ID. Runtime implementations must supply real hook behavior through `PluginRuntime` and
route invocations through the supplied controller. Cleanup failures remain visible and may quarantine a plugin.
Replacement requires enough engine capacity to construct a candidate generation while the old generation remains
active. Engines that cannot do so fail candidate creation and retain the old runtime. The short drain interval rejects
new invocations by design. Opaque state migration cannot transfer engine-native objects, and publication cannot be
rolled back after old-runtime cleanup begins; cleanup errors are surfaced while the new generation remains active.
