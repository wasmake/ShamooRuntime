# Architecture

## Dependency direction

```text
runtime-protocol <- runtime-core <- runtime-javet
       ^                 ^              ^
       |                 |              |
runtime-codegen-support  platform-* <- bootstrap-*
                            ^
                            |
                       integration-*
```

`runtime-protocol` and `runtime-core` have no Paper, Velocity, Javet, or swc4j types in their public surface.
Platform adapters implement `RuntimeHost`; they do not own a script engine. Bootstrap entry points are composition
roots. `ShamooNodeRuntimeManager` owns one isolated Node runtime per admitted script plugin.

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

## Artifact boundaries

Library modules publish normal Java library JARs with source and Javadoc variants. The bootstrap modules set distinct
artifact names and contain only their corresponding platform entry point. They do not combine both platform APIs or
silently shade native libraries.

## Current limits

Phase 5 enforces a deny-by-default subset of `NodePolicy` at runtime-controlled boundaries. Native Node paths that
Javet cannot mediate securely remain disabled even when requested. TypeScript transformation, hot reload, dependency
installation, distribution shading, and full server-process test harnesses remain outside this phase.
