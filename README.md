# ShamooRuntime

ShamooRuntime is a Java 21 foundation for embedding a JavaScript runtime in Paper and Velocity plugins. Phase 1
establishes module boundaries, lifecycle ownership, immutable protocol types, platform scheduling adapters, and
separately packaged bootstrap artifacts. Script APIs, generated bindings, sandbox policy, and production server
integration belong to later phases and are not represented as complete here.

## Requirements

- Java 21
- Linux x86-64 for the Phase 1 pinned Javet/V8 native runtime
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
| `runtime-protocol` | Versioned requests and results shared across boundaries |
| `runtime-core` | Platform-neutral runtime and host contracts |
| `runtime-javet` | Javet/V8 implementation and native lifecycle |
| `runtime-codegen-support` | Binding annotations and generated metadata validation |
| `platform-paper` | Paper scheduler/logging adapter |
| `platform-velocity` | Velocity scheduler/logging adapter |
| `bootstrap-paper` | Paper plugin entry point artifact |
| `bootstrap-velocity` | Velocity plugin entry point artifact |
| `integration-paper` | Paper runtime smoke-probe support |
| `integration-velocity` | Velocity runtime smoke-probe support |

See [`docs/architecture.md`](docs/architecture.md) and [`docs/adr`](docs/adr) for dependency and lifecycle decisions.

## License

Licensed under the Apache License 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).
