# Plugin Lifecycle

Phase 6 adds production lifecycle coordination to `runtime-core`. The coordinator is engine-neutral. Runtime
implementations enter through `PluginRuntimeFactory` and `PluginRuntime`; `runtime-core` does not import Javet or a
platform API. `JavetPluginRuntimeFactory` is the composition adapter for `ShamooNodeRuntimeManager` and delegates hook
execution to an injected `PluginRuntime` implementation.

## Discovery and candidates

`PluginDiscovery` inventories direct child directories of the configured runtime plugins directory. Every candidate
must contain `shamoo-plugin.json`. Parsing uses the strict manifest codec, and the resulting descriptor name is the
plugin identity. Discovery rejects symbolic links, path escapes, special files, malformed descriptors, unstable file
sets, and every candidate involved in a duplicate identity. It reads metadata and SHA-256 content checksums twice,
separated by the configured stability window. Inventory paths and output ordering are deterministic.

An `InstalledPluginCandidate` is an immutable descriptor, canonical root, identity, and checksum inventory. A
`PluginStager` can prepare that model below a staging root. Phase 6 intentionally does not replace an installed
candidate or perform a transactional directory swap.

## State machine

The exact states are:

```text
DISCOVERED BLOCKED LOADING LOADED LOAD_FAILED ENABLING ENABLED ENABLE_FAILED
READYING READY READY_FAILED DRAINING DRAIN_FAILED DISABLING DISABLED DISABLE_FAILED
UNLOADING UNLOADED UNLOAD_FAILED QUARANTINED
```

`PluginLifecycleMachine` owns the only legal-transition table. State changes and history append under the same lock,
so observers cannot see one without the other. Every history entry contains immutable source and target states, UTC
time, reason, and caller correlation UUID. Snapshots return immutable copies. Illegal transitions raise
`IllegalLifecycleTransitionError` without changing state or history.

Retries are represented explicitly in the transition table. Hook failure always enters its matching `*_FAILED`
state before policy can move it to `QUARANTINED`. Load, enable, ready, drain, disable, and unload retries are legal only
from their matching failure state. A blocked candidate can return to `DISCOVERED` only after dependency reevaluation.
An unloaded candidate is terminal in Phase 6.

## Dependencies

`PluginDependencyGraph` evaluates required and optional NPM-compatible semantic-version ranges. A present compatible
optional dependency participates in ordering; a missing or incompatible optional dependency does not block. Required
missing, incompatible, or blocked dependencies produce stable `DependencyBlock` codes. `loadBefore` and `loadAfter`
add ordering edges only when their target exists.

Topological ordering uses plugin identity as its tie-breaker. Complete cycle paths repeat the starting identity at the
end and are canonicalized lexically. Required dependencies enable before dependents. Disable operations walk the
reverse graph, including when an administrator disables one dependency directly, so dependents drain and disable
first. Rebuilding the graph reevaluates compatibility and updates `BLOCKED` candidates atomically with installation.

## Coordination and invocations

Calls for one plugin are serialized, including concurrent callers. A phase already in its completed state is
idempotent. Hooks and runtime creation have configured timeouts. Structured `LifecycleError` subclasses retain plugin
identity, phase, correlation UUID, stable code, and cause. The invocation controller opens only after `READY`, rejects
new work as draining starts, counts admitted/completed/rejected work, and completes drain waits only at zero active
leases. A timed-out drain enters `DRAIN_FAILED`; its explicit retry repeats drain and the zero-active wait.

`QuarantinePolicy` defines the repeated-failure threshold and whether cleanup leaks quarantine immediately. A
quarantined plugin rejects lifecycle progress except unload. Quarantine never erases the exact preceding failure
transition.

## Resource ownership

`ResourceRegistry` records a plugin owner, category, description, registration UUID, timestamp, and close action.
Categories cover generic resources, listeners, commands, tasks, timers, files, watchers, proxies, services, messages,
and pending invocations without constructing platform resources. Cleanup is LIFO per owner and continues after
errors. Failed closes remain registered as leaks and can be retried. `ResourceCleanupReport` aggregates every error,
and registry snapshots expose live registrations. The original generic registration method remains available for
runtime-native resources.

## Administration

`PluginIntrospectionSnapshot` combines current state, transition audit history, graph dependencies and block reasons,
owned resources, invocation counters, lifecycle metrics, and capture time. Snapshot values are immutable and contain
only core/protocol types.
