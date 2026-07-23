# ADR 0005: Generated platform and NMS boundaries

## Status

Accepted for Phases 7 and 8.

## Decision

Stable Paper and Velocity bindings are generated from pinned API artifacts by reading class files with ASM. The
scanner never loads target classes. Its canonical model retains generic signatures, inheritance, records, enums,
annotations and type annotations, deprecation, declared exceptions, functional interfaces, arrays, varargs, and
default methods. Every supported public type/member receives a descriptor; generation fails unless coverage is 100%.
The only stable exclusions are internal implementation packages listed in each coverage report. Event registries are
derived from inheritance and naming rules, never handwritten event lists.

Paper NMS is a separate exact-Minecraft-version registry generated from a Mojang-mapped server or Paper dev-bundle
artifact with `generatePaperNms`. Packet IDs include the Minecraft version. Runtime packet access requires both an
operator opt-in and a per-plugin allowlist entry. Plugins receive opaque live handles; they cannot request classes,
reflect methods, unwrap packet objects, or pass unregistered packet classes.

Netty interception is installed per connection immediately before an exact-version pipeline anchor supplied by the
Paper server integration. All pipeline changes and forwarding run on the channel event loop. A bounded pending count
and timeout prevent unbounded origin-frame retention. Injection resources remove themselves on plugin cleanup and
channel close.

## Consequences

Packet mutation and every NMS descriptor are unstable and may break on any Paper or Minecraft update. The pinned
`paper-api` artifact does not contain CraftBukkit, Mojang server classes, a player-to-channel accessor, or protocol
registration tables. Consequently the stable artifact can implement interception once given the live player channel,
but generating NMS descriptors and wiring the channel accessor requires the exact Paper server/dev-bundle artifact.
No reflective fallback is permitted when that artifact is unavailable.
