# Architecture

## Dependency direction

```text
runtime-protocol <- runtime-core <- runtime-javet
       ^                 ^              ^
       |                 |              |
runtime-codegen-support  platform-* <- platform-paper-nms <- bootstrap-paper
                             ^
                             |
                        integration-*
```

`runtime-protocol` and `runtime-core` have no Paper, Velocity, Javet, or swc4j types in their public surface.
Platform adapters implement `RuntimeHost`; they do not own a script engine. Bootstrap entry points are composition
roots. `PluginLifecycleCoordinator` owns discovery admission, graph ordering, hooks, invocation draining, and resource
cleanup through core interfaces. `JavetPluginRuntimeFactory` adapts those interfaces to
`ShamooNodeRuntimeManager`, which owns one isolated Node runtime per admitted script plugin. The NMS module is compiled
and reobfuscated only into the Paper bootstrap and is absent from Velocity's dependency graph.

Stable platform descriptors are generated from pinned bytecode without classloading. Paper NMS descriptors and packet
transport are a separate exact-version boundary and are never imported by Velocity. See `platform-adapters.md` and ADR
0005.

## Runtime contract

`ScriptRuntime.execute` accepts immutable `ScriptRequest` values and returns asynchronous `ScriptResult` values.
Protocol compatibility requires a matching major and a runtime minor newer than or equal to the required minor. This
rule applies to manifest admission against `ProtocolVersion.CURRENT` and to generated binding metadata.

Each Javet Node isolate is created, accessed, pumped, and closed on its own dedicated platform thread. A bounded queue
provides backpressure and external calls return futures. Virtual ESM and CommonJS loading, direct host callbacks,
secure descriptor-relative filesystem operations, and immutable permissions form the controlled boundary. Filesystem
operations are denied where the provider cannot supply secure directory streams. Phase 5 wires Javet's Linux
x86-64 Node native artifact; additional operating systems and CPU architectures require explicit distribution
variants rather than runtime guessing. See [`runtime.md`](runtime.md) and ADR 0003.

Runtime plugin directories are inventoried only after a stable-file window. Strict descriptors and SHA-256 inventories
form immutable installed candidates. A deterministic dependency graph controls enable and reverse-disable order.
Lifecycle calls are serialized per plugin, active invocations drain before disable, and typed plugin-owned resources
clean up in reverse order. See [`lifecycle.md`](lifecycle.md) and ADR 0004.

## Artifact boundaries

Library modules publish normal Java library JARs with source and Javadoc variants. Bootstrap artifacts set distinct
platform names; the Paper artifact includes and reobfuscates its mapped NMS boundary, while Velocity does not load or
package it.

## Current limits

The runtime enforces a deny-by-default subset of `NodePolicy` at runtime-controlled boundaries. Native Node paths that
Javet cannot mediate securely remain disabled even when requested. Phase 6 defines staging but not transactional
candidate swap or rollback. TypeScript transformation, hot reload, dependency installation, distribution shading,
remain outside the current implementation. Full Paper and Velocity process harnesses are opt-in Gradle tasks and CI
workflow-dispatch jobs because each downloads and starts a complete server.
