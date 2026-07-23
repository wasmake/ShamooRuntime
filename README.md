# ShamooRuntime

ShamooRuntime is a Java 21 foundation for embedding a JavaScript runtime in Paper and Velocity plugins. The runtime
defines module and lifecycle boundaries, separately packaged platform adapters, the plugin manifest protocol, and a
Phase 6 plugin lifecycle foundation. Manifest v1 provides strict JSON decoding, immutable policy models,
semantic-version negotiation, and admission validation. Secure discovery, deterministic dependency ordering,
serialized hooks, invocation draining, quarantine, and typed resource ownership coordinate each confined plugin Node
isolate without exposing Javet through core APIs. Phases 7 and 8 add generated stable platform surfaces, owned Paper
and Velocity adapters, and a separately versioned opt-in Paper packet boundary.

## Requirements

- Java 21
- Linux x86-64 for the pinned Javet Node native runtime
- The checked-in Gradle wrapper

## Build

```bash
./gradlew build
```

Run all configured verification, including Checkstyle, PMD, SpotBugs, tests, and artifact assembly:

```bash
./gradlew check
```

Paper and Velocity entry points are built independently:

```bash
./gradlew :bootstrap-paper:reobfJar :bootstrap-velocity:jar
```

The bootstrap JARs currently preserve dependencies as separate artifacts; dependency bundling and distribution
packaging are intentionally deferred until runtime loading policy is defined.

## Modules

| Module | Responsibility |
| --- | --- |
| `runtime-protocol` | Versioned requests, plugin manifests, and compatibility negotiation |
| `runtime-core` | Discovery, dependency graph, lifecycle coordination, resources, and host contracts |
| `runtime-javet` | Per-plugin Javet Node implementation, policy boundary, event loop, and native lifecycle |
| `runtime-codegen-support` | Binding annotations and generated metadata validation |
| `platform-paper` | Paper events, commands, Folia schedulers, messaging, Adventure, and opt-in packets |
| `platform-paper-nms` | Exact Paper 1.21.8 mapped connection and packet integration |
| `platform-velocity` | Velocity continuation events, commands, scheduler, messaging, routing, and Adventure |
| `bootstrap-paper` | Paper plugin entry point artifact |
| `bootstrap-velocity` | Velocity plugin entry point artifact |
| `integration-paper` | Paper runtime smoke-probe support |
| `integration-velocity` | Velocity runtime smoke-probe support |

See [`docs/protocol.md`](docs/protocol.md), [`docs/runtime.md`](docs/runtime.md),
[`docs/lifecycle.md`](docs/lifecycle.md), [`docs/architecture.md`](docs/architecture.md), and [`docs/adr`](docs/adr) for
wire, dependency, and lifecycle decisions.
Platform generation, threading contracts, cleanup, and exact-version NMS limits are documented in
[`docs/platform-adapters.md`](docs/platform-adapters.md).

Generate pinned stable API registries with `./gradlew :runtime-codegen-support:generatePlatformApis`. Generate the
separate unstable NMS registry with
`./gradlew :runtime-codegen-support:generatePaperNms -PnmsVersion=<version> -PnmsArtifact=<mapped-server.jar>`.

The in-process runtime is defense in depth, not an OS security sandbox. Unsupported native Node capabilities remain
denied even when a manifest requests them. See [`SECURITY.md`](SECURITY.md) for the threat boundary.

## License

Licensed under the Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
