# ADR 0006: Version Cross-Plugin Contracts and Proxy Messaging

- Status: Accepted
- Date: 2026-07-23

## Context

Plugin runtime generations are replaced transactionally. Direct references to a provider implementation would retain
the retired generation, bypass invocation draining, and make resource cleanup unsafe. Paper plugin messaging also
requires an online player carrier and may run with no Velocity proxy at all. Velocity normally forwards plugin
messages, so an endpoint must not consume traffic it does not understand.

## Decision

Identify services and events by a validated stable name and semantic version. Consumers select an NPM-compatible
version range. A service lookup returns a stable core proxy, not a provider object. Every invocation resolves the
currently active compatible generation, acquires that provider's invocation lease, and releases it when the async call
finishes. Providers, proxies, and event subscriptions are plugin-owned resources. Generation publication and removal
follow lifecycle admission. Event delivery uses the same compatibility and subscriber-admission rules.

Each service proxy declares `KEEP_RUNNING` or `RELOAD`. During provider replacement, reload dependents drain in reverse
order and return in enable order around publication. A failed pre-publication replacement restores those dependents.
Stable proxies continue resolving without retaining either provider generation.

Use `shamoo:runtime_v1` for optional Paper to Velocity communication. `runtime-protocol` owns a strict binary envelope
with magic value, protocol version, request/response/error role, RFC 4122 UUID request identifier, exact contract
identity, operation, and opaque bytes or a structured bounded error. The payload is deliberately JSON-neutral. Decoding
rejects unsupported versions, malformed UTF-8 or lengths, trailing data, invalid identifiers or semantic versions, and
payloads over 30,000 bytes. Requests correlate to bounded-time pending responses; an absent proxy, failed send, or
absent Paper player carrier yields an explicit unavailable result.

Velocity accepts requests only on the registered identifier and only from a backend `ServerConnection`. It preserves
the existing `PluginMessageEvent` forwarding result for other channels, malformed envelopes, responses, and untrusted
sources. It marks a validated request handled before asynchronous dispatch and returns a correlated response or a
non-sensitive error code. Platform access is composed through named `PlatformCapabilities`; no messenger, event
manager, server registry, or reflective host object enters runtime-core.

## Consequences

Contract compatibility is explicit and provider reload cannot leave consumers holding stale implementation objects.
Invocation draining includes cross-plugin calls and event deliveries. Reload-aware dependents incur a deliberate
restart, while stable-proxy consumers remain active. Standalone Paper behavior is unchanged except that proxy requests
report unavailable until an online carrier exists. The envelope protects transport framing, but authorization of
individual operations remains the responsibility of the allowlisted endpoint.
