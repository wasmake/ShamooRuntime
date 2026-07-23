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
roots and own exactly one `ScriptRuntime` from platform initialization through shutdown.

## Runtime contract

`ScriptRuntime.execute` accepts immutable `ScriptRequest` values and returns asynchronous `ScriptResult` values.
The protocol major version is the compatibility boundary. A runtime with a matching major and a newer or equal
minor version can consume older generated binding metadata.

Javet isolate access is serialized through a dedicated single virtual-thread executor. Closing the runtime first
prevents new submissions, closes V8, and shuts down that executor. Platform work crosses the boundary only through
`RuntimeHost.dispatch`. Phase 1 wires Javet's Linux x86-64 V8 native artifact; additional operating systems and CPU
architectures require explicit distribution variants rather than runtime guessing.

## Artifact boundaries

Library modules publish normal Java library JARs with source and Javadoc variants. The bootstrap modules set distinct
artifact names and contain only their corresponding platform entry point. They do not combine both platform APIs or
silently shade native libraries.

## Phase 1 limits

This foundation does not define sandboxing, user-facing scripting APIs, module loading, TypeScript transformation,
hot reload, dependency shading, or full server-process test harnesses. Those capabilities require explicit design
and threat modeling before implementation.
