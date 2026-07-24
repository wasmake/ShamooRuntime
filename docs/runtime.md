# Per-plugin Node runtime

## Ownership and submission

`ShamooNodeRuntimeManager` keys each `ShamooNodeRuntime` by plugin identity and generation UUID. Active and staged
generations can coexist during replacement, while duplicate creation of the same generation is rejected. Every instance creates its
Javet `NodeRuntime` on a dedicated daemon platform thread. Creation, V8 access, event-loop pumping, module resolution,
callbacks, resource release, and native close stay on that thread. The Javet object is never returned to callers.

Public operations marshal work through a configurable bounded queue. A full queue fails immediately with
`RuntimeQueueFullError`; shutdown rejects new work with `RuntimeDisposedError`. `RuntimeMetricsSnapshot` reports only
measured queue, invocation, rejection, callback, resource, source-map, and asynchronous-error counts. Javet 5.0.9
does not expose a stable active-libuv-handle count, so this runtime does not estimate one.

## Execution

- `evaluate()` executes a named script and converts its result to a Java value.
- A returned promise is pumped on the owner thread with Javet's Node `await()` API until fulfilled or rejected.
- Zero-delay timers and Node tasks execute through the real Node event loop, not a Java timer simulation.
- Script and CommonJS execution both await exported promises, pump pending Node tasks, and perform the same deferred
  unhandled-rejection checkpoint before completing.
- `registerModule()` installs an in-memory virtual module. `executeModule()` supports V8 ESM and a controlled
  CommonJS wrapper with a per-runtime cache. Relative `require()` and `__dirname` resolve from the requiring module.
- ESM imports resolve only registered `plugin:/` modules or mediated safe builtins. CommonJS `require()` has the same
  restriction. Absolute names, traversal, unregistered files, packages, and URL imports are denied.
- `IV8Executor.setResourceName()` is intentionally not used because Javet's Node implementation also changes the
  process working directory and native require root. The runtime writes the `V8ScriptOrigin` resource name directly.
- V8 guards apply the configured invocation timeout to scripts, ESM, and CommonJS wrapper compilation and invocation.
  Timeouts are configured at millisecond precision and values below one millisecond are rejected.

Javet 5.0.9 supports static ESM through `V8Module`, synthetic builtin modules, CommonJS through Node's native require,
and event-loop pumping. Shamoo uses native require only behind its private resolver. It does not expose unrestricted
Node CommonJS loading because that path bypasses operation-level policy checks.

## Bindings and errors

Host functions are direct Javet callbacks registered under explicit names on a frozen, null-prototype `host` object.
The global property cannot be replaced. Before Javet conversion, both directions accept only scalar data, byte arrays,
and acyclic string-keyed lists/maps. Java/Javet objects, classes, class loaders, reflection members, dynamic proxies,
and arbitrary service objects are rejected. No Java object proxy converter, class lookup, or reflection bridge is
installed. JS functions cross only through `host.registerCallback(name, function)`; subsequent generated operations
refer to the callback by name or an explicit `{$callback: name}` marker. Java `CompletionStage` results become native
Promises through an isolate completion queue, so no foreign thread accesses V8. Callback functions and contexts are
tracked and removed on close.

Evaluation failures preserve the Javet cause in `RuntimeEvaluationError`. Errors carry plugin identity, script stack,
and a one-based `SourcePosition` when Javet provides one. `SourceMapRegistry` maps exact generated positions to
original positions deterministically. Module and policy failures use `RuntimeModuleResolutionError` and
`RuntimePermissionError`. Rejections returned to and awaited by an evaluation are reported only through that
evaluation. Other promise rejections are deferred through an event-loop turn and delivered, if still unhandled, with
Node `uncaughtException` events to the runtime's `RuntimeErrorReporter` as `RuntimeUnhandledError` on the owner thread.
Source-map registration is admitted through the same owner-thread queue as evaluation and canonicalizes generated
resource names to `plugin:/` names. CommonJS wrapper line offsets are removed before exact-position lookup.
The `JavetScriptRuntime` protocol adapter converts only `RuntimeEvaluationError` to `ScriptResult.FAILURE`; lifecycle,
admission, permission, and module errors remain exceptional completions for callers to handle.

## Permissions

`RuntimePermissions` is an immutable canonical projection of manifest `NodePolicy`. Builtin aliases are normalized to
`node:*`; filesystem entries remain plugin-root-relative. The implemented boundaries are deliberately narrower than
the manifest model:

| Capability | Runtime behavior |
| --- | --- |
| `node:assert`, `node:buffer`, `node:path`, `node:querystring`, `node:string_decoder`, `node:url`, `node:util` | Available only when allow-listed, through controlled CommonJS/ESM resolution |
| Filesystem | Native `node:fs` denied; `readTextFile()` and `writeTextFile()` enforce relative allowlists with descriptor-relative, no-follow traversal when `SecureDirectoryStream` is available, and deny access otherwise |
| Network | Native modules and global `fetch`, `WebSocket`, and `EventSource` denied |
| Child processes, workers, clusters | Denied |
| Native addons and arbitrary packages | Denied |
| `Buffer` | Exposed only when `node:buffer` is allow-listed; otherwise removed |
| `process`, global `require`, `module`, `exports` | Removed before plugin execution |
| `BroadcastChannel`, `MessageChannel`, `MessageEvent`, `MessagePort` | Removed; cross-isolate messaging is not exposed |

A policy request does not imply implementation support. Network, workers, child processes, native addons, and native
filesystem access remain denied even if requested because Javet's in-process Node embedding cannot securely mediate
every native operation on those paths.

## Close

Close atomically stops admission, fails queued work with `RuntimeDisposedError`, places cleanup after the active
guarded invocation, and shuts down the executor. On the owner thread it marks Node stopping, closes CommonJS exports,
direct callbacks, callback contexts, Java bindings, registered resources, and resolver-created V8 modules, pumps
pending work without waiting, and force-closes the Node runtime. Cleanup continues after individual failures, which
are aggregated, and native close is always attempted. Concurrent callers wait on one shared close completion; the
manager retains the exact generation key until it completes. Creation interruption or timeout similarly waits until the
owner has closed any native runtime before throwing. JavaScript execution is bounded by `V8Guard`; close waits for the
invocation and close timeouts and preserves interruption. Javet cannot preempt Java code inside a direct host callback, so trusted callback
implementations must not block indefinitely. A native crash or a Javet/V8 defect cannot be made recoverable by Java
lifecycle code.
