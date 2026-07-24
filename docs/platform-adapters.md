# Platform adapters

## Generated stable APIs

`runtime-codegen-support` reads the pinned Paper, Velocity, and platform-specific Adventure JARs with ASM. It does not
load their classes. Run:

```bash
./gradlew :runtime-codegen-support:syncPlatformApis
./gradlew :runtime-codegen-support:diffPlatformApis
```

Canonical `model.json` files follow `@shamoo/platform-codegen`'s `JvmApiModel` schema version 1 and are sorted and
reproducible. Coverage denominators come from the scanner inventory captured before model serialization; generation
fails if an eligible declaration or member is omitted. Coverage also reports exceptions, events, packets, and packet
registrations. The Paper and Velocity bootstrap JARs package only their own public model.

The checked-in pinned surfaces contain 2,165 Paper declarations, 30,370 members, and 422 events, plus 358 Velocity
declarations, 2,664 members, and 46 events. A changed pinned artifact changes these files and `diffPlatformApis` fails.

## Paper

`PaperEventBridge` registers a stable listener at a requested Bukkit priority. The dispatcher receives the original
live event and must complete synchronously in the originating event frame; cancellation, result, and other mutations
therefore remain native Bukkit state. Closing unregisters the listener.

`PaperCommandBridge` selects the command-map capability because the pinned lifecycle registrar has no supported
immediate unregister operation. Close removes primary, alias, and namespaced labels synchronously. `PaperSchedulerBridge` uses Paper's
async, global, region, and entity schedulers and exposes current-region ownership checks. Every listener, command,
task, and messaging channel is registered in `ResourceRegistry`. `PaperAudienceBridge` passes native Adventure
audiences without serialization.

Bootstraps publish only named platform operations into Javet. Every invocation requires generated namespace/type and
protocol metadata, binds ownership to the calling plugin, and registers listeners, commands, tasks, channels, and packet
subscriptions in `ResourceRegistry`; server, proxy, registrar, scheduler, and packet registry objects are never exposed.
Script dispatchers are explicit registered callback names. Paper event callbacks are synchronously joined in the
native event frame, scheduled work uses Paper/Folia schedulers, and Velocity event callbacks return the native
continuation stage. Only copied scalar/list/map/byte carriers cross the isolate boundary. Paper proxy requests select
an eligible online player automatically rather than exposing a `Player` carrier to JS.

Generated invocation identities are cached on immutable strings and protocol values, never event classes, plugin
instances, runtimes, or class loaders. Paper packet subscribers use a copy-on-write array prepared only when
subscriptions change, so dispatch does not allocate a subscriber snapshot. Velocity reuses native
`CompletableFuture` instances instead of wrapping every event completion while preserving generic `CompletionStage`
support. Regression tests assert the common `CompletableFuture` event path returns the identical future, making the
removed per-dispatch adapter allocation observable without relying on timing benchmarks.

## Velocity

`VelocityEventBridge` returns `EventTask.resumeWhenComplete` for each dispatch. Velocity owns the continuation and
thread selection; the adapter does not invent a main thread. The original event remains live through completion.
Commands use public Brigadier, simple, and raw APIs and unregister by `CommandMeta`. Simple/raw dispatcher targets can
be atomically replaced while command identity remains stable. Scheduler work is asynchronous. Messaging preserves raw
bytes, routing uses public connection requests, and audiences remain native Adventure objects.

## Paper packets and NMS

`platform-paper-nms` pins paperweight, Paper 1.21.8 build 55, and Mache build 2. Generate its mapped models with:

```bash
./gradlew :platform-paper-nms:generatePaperNmsModels
```

The task scans paperweight's deterministic mapped server output and checks in 6,004 NMS/CraftBukkit declarations. An
isolated, bootstrapped generator JVM reads the mapped protocol registration API and emits 219 packet classes with 244
state/direction registrations and 100% numeric ID coverage. `PacketAccessPolicy` requires `packets.enabled` plus a
plugin allowlist. Direct mapped
access follows the exact server listener, accepted `Connection`, and its Netty channel. A server acceptor hook installs
before handshake decoding, covers status, login, configuration, and play in both directions, sends through the native
packet listener, and removes handlers on disconnect or shutdown. Startup rejects any Minecraft version or Paper build other
than the exact compiled target.

Nightly and manually dispatched process tests cache immutable Paper 1.21.8 build 55 and Velocity 3.4.0 build 566 artifacts, install the built
bootstrap, wait for the runtime readiness probe, stop cleanly, and retain logs:

```bash
./gradlew paperProcessIntegration
./gradlew velocityProcessIntegration
```

The CI process tasks launch each platform independently. A combined Paper-plus-Velocity transport process is not run:
the pinned harness has no backend login client, so Bukkit plugin messaging cannot obtain the required live player
carrier. Transport codec, endpoint trust, carrier invalidation, and request timeout behavior are covered in JVM tests;
the missing authenticated player connection is the exact external process-fixture blocker.
