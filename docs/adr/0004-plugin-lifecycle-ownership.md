# ADR 0004: Core-owned Plugin Lifecycle and Resources

- Status: Accepted
- Date: 2026-07-23

## Context

Plugin discovery, dependency ordering, hook execution, active invocation draining, and host resource cleanup must have
one deterministic owner. Putting those decisions in Paper, Velocity, or Javet would duplicate behavior and expose
implementation-specific objects across module boundaries. Updating an installed plugin also needs a candidate model,
but transactional replacement has separate rollback and filesystem durability requirements.

## Decision

Keep lifecycle policy in `runtime-core`. Use one centrally defined, thread-safe state machine with immutable correlated
history. Discover stable directory candidates with strict descriptors, path defenses, and checksum inventories.
Resolve required and optional semver dependencies plus ordering hints into deterministic enable and reverse-disable
orders, explicit block reasons, and complete cycle paths.

Serialize each plugin's hooks through core-owned runtime interfaces, apply bounded hook and drain waits, and admit
invocations only while ready. Register host and engine resources by plugin and category, clean them in reverse order,
retain failed closes as observable leaks, and apply an explicit quarantine policy. Expose immutable administrative
snapshots. Javet is connected by an adapter in `runtime-javet`; no Javet type enters the core contract.

Define staging and installed candidate abstractions now, but defer transactional swap, rollback, and persisted update
journals to a later phase.

## Consequences

Paper and Velocity can share identical lifecycle behavior, dependency order is reproducible, and failures retain an
auditable phase and correlation ID. Runtime implementations must supply real hook behavior through `PluginRuntime` and
route invocations through the supplied controller. Cleanup failures remain visible and may quarantine a plugin.
In-place candidate replacement is rejected rather than pretending to provide transactional safety.
