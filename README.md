# ShamooRuntime

ShamooRuntime is a Java 21 foundation for embedding a JavaScript runtime in Paper and Velocity plugins. The runtime
defines module and lifecycle boundaries, separately packaged platform adapters, the plugin manifest protocol, and a
Phase 5 per-plugin Javet Node runtime foundation. Manifest v1 provides strict JSON decoding, immutable policy models,
semantic-version negotiation, and admission validation. Each plugin Node isolate has a confined owner thread, bounded
queue, controlled virtual modules and callbacks, deny-by-default policy, structured errors, and deterministic cleanup.

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
./gradlew :bootstrap-paper:jar :bootstrap-velocity:jar
```

The bootstrap JARs currently preserve dependencies as separate artifacts; dependency bundling and distribution
packaging are intentionally deferred until runtime loading policy is defined.

## Modules

| Module | Responsibility |
| --- | --- |
| `runtime-protocol` | Versioned requests, plugin manifests, and compatibility negotiation |
| `runtime-core` | Platform-neutral runtime, host contracts, and manifest admission |
| `runtime-javet` | Per-plugin Javet Node implementation, policy boundary, event loop, and native lifecycle |
| `runtime-codegen-support` | Binding annotations and generated metadata validation |
| `platform-paper` | Paper scheduler/logging adapter |
| `platform-velocity` | Velocity scheduler/logging adapter |
| `bootstrap-paper` | Paper plugin entry point artifact |
| `bootstrap-velocity` | Velocity plugin entry point artifact |
| `integration-paper` | Paper runtime smoke-probe support |
| `integration-velocity` | Velocity runtime smoke-probe support |

See [`docs/protocol.md`](docs/protocol.md), [`docs/runtime.md`](docs/runtime.md), [`docs/architecture.md`](docs/architecture.md), and
[`docs/adr`](docs/adr) for wire, dependency, and lifecycle decisions.

The in-process runtime is defense in depth, not an OS security sandbox. Unsupported native Node capabilities remain
denied even when a manifest requests them. See [`SECURITY.md`](SECURITY.md) for the threat boundary.

## License

Licensed under the Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
