# ADR 0003: Confined Per-plugin Node Runtimes

- Status: Accepted
- Date: 2026-07-23

## Context

Javet's V8 and Node objects are native, stateful, and thread-sensitive. Native Node module loading also reaches
filesystem, network, process, worker, and addon capabilities without a complete Java interception point. Sharing an
isolate or exposing Javet objects would make plugin state, lifecycle, and policy enforcement ambiguous.

## Decision

Create one Node isolate per plugin on one dedicated platform thread. All external work enters a bounded executor and
all Javet access, including creation and close, remains on that thread. Expose only Java futures, immutable metrics,
structured errors, virtual modules, canonical filesystem operations, and explicitly registered direct callbacks.

Disable Javet's native builtin ESM resolution and remove unrestricted Node globals. Resolve registered virtual ESM
and CommonJS modules in the runtime. Permit only a small audited builtin set when the manifest requests it. Deny any
native capability that cannot be mediated reliably instead of treating manifest intent as proof of enforcement.

## Consequences

Plugins have independent globals and module caches, queue pressure is observable, and shutdown has explicit resource
ownership. Native Node package loading and broad Node compatibility are intentionally reduced. This architecture is
defense in depth inside one JVM, not a security boundary equivalent to an operating-system process or container.
