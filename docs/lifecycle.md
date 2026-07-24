# Plugin Lifecycle

Phases 6 and 9 provide production lifecycle and hot-replacement coordination in `runtime-core`. The coordinator is
engine-neutral. Runtime
implementations enter through `PluginRuntimeFactory` and `PluginRuntime`; `runtime-core` does not import Javet or a
platform API. `JavetPluginHost` is the production composition root used by both bootstraps; it owns compatibility
admission, discovery, watcher transactions, administration, coordinator, and all generation-keyed runtimes.
`JavetPluginRuntime` loads the selected platform entrypoint and invokes lifecycle and bounded hot-state hooks.

## Discovery and candidates

`PluginDiscovery` inventories direct child directories of the configured runtime plugins directory. Every candidate
must contain `shamoo-plugin.json`. Parsing uses the strict manifest codec, and the resulting descriptor name is the
plugin identity. Discovery rejects symbolic links, path escapes, special files, malformed descriptors, unstable file
sets, and every candidate involved in a duplicate identity. It reads metadata and SHA-256 content checksums twice,
separated by the configured stability window. Inventory paths and output ordering are deterministic.

An `InstalledPluginCandidate` is an immutable descriptor, canonical root, identity, and checksum inventory. A
`PluginStager` prepares that model below a staging root. `stageAndReplace` never loads the mutable source directory;
staging and all descriptor, identity, checksum, and dependency validation finish before the active runtime is changed.

`PluginDirectoryWatcher` recursively watches the plugin root, coalesces all changes belonging to one direct-child
candidate, and emits create, change, and delete paths only after the configured quiet window. It ignores the internal staging directory and
reports observation failures separately from candidate events. Discovery still performs its two-inventory stability
check after notification, so watcher debounce is not treated as validation.

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
An unloaded generation is terminal; reinstalling that identity creates a new generation and history.

## Transactional replacement

Replacement operations run on the same serialized lifecycle queue as ordinary hooks. The coordinator first resolves
a complete hypothetical dependency graph. It rejects a blocked candidate and any replacement that would newly block
an active dependent. It then creates a candidate generation with separate invocation admission and resource ownership,
runs load, enable, and ready without exposing it to callers, and optionally migrates state. The old generation remains
`READY` and accepting throughout this preparation. Candidate creation or hook/import failure unloads and cleans only
the candidate generation; the installed candidate, graph, old runtime, and old admission remain unchanged.

When preparation succeeds, old admission closes, the old runtime's drain hook runs, and active leases reach zero
before disable. The managed-runtime reference, candidate, dependency resolution, and candidate admission switch in one
coordinator critical section. Calls after that point can reach only the new generation. The old runtime is then
unloaded and its generation resources are closed. An old cleanup failure is reported to the replacement caller but
does not roll back a new runtime that is already active; leaked registrations remain visible in introspection.

State transfer is opt-in twice: the candidate manifest must set `reload.preserveState`, and both generations must
implement `HotStatePluginRuntime`. The contract transfers an opaque copied byte array, keeping engine objects out of
core. Export and import use lifecycle timeouts and complete before old admission is stopped. If either runtime does not
implement the optional contract, replacement proceeds without state transfer.

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
only core/protocol types. Resource entries also include live leaks retained from retired generations.

`JavetPluginHost` exposes install, enable, disable, unload, reload, snapshot, and runtime-count operations. Shutdown
stops the watcher, disables and unloads in dependency order, closes every remaining native generation, and terminates
the administration executor.
