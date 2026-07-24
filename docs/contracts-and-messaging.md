# Cross-Plugin Contracts and Proxy Messaging

## Services

`PluginRuntimeContext.services()` is generation-scoped. Providers register an exact `ServiceContract` semantic version
and an engine-neutral async handler. Consumers acquire a service name with a `SemverRange` and a
`DependentReloadPolicy`. The returned `PluginServiceProxy` is stable: it resolves the highest compatible active
provider on every call and never exposes or retains the provider runtime.

Calls acquire the provider generation's `InvocationAdmission` lease. A draining, disabled, absent, or incompatible
provider produces `ServiceUnavailableException`. Provider registrations and consumer proxies are owned resources, so
unload removes them automatically. `RELOAD` consumers drain before their provider is published and re-enable in graph
order afterward; `KEEP_RUNNING` consumers use the stable proxy without restarting.

## Events

`PluginRuntimeContext.events()` publishes an exact `EventContract` and subscribes with a compatible semantic-version
range. Only active compatible subscriber generations receive the event. Every async delivery holds the subscriber's
invocation lease and subscription cleanup follows generation resource ownership. Payloads are engine-neutral objects;
platform event instances are not cross-plugin contracts.

## Paper and Velocity

The optional transport uses channel `shamoo:runtime_v1`. Multi-byte integers use network byte order. Every frame starts
with this 22-byte header:

```text
u32 magic "SHMP" | u8 version=1 | u8 role | 16-byte RFC 4122 request UUID
```

The role body immediately follows the header:

```text
request (role 0): u16 contract-id bytes | u16 exact-semver bytes | u16 operation bytes | u32 payload bytes
                  | contract-id UTF-8 | exact-semver UTF-8 | operation UTF-8 | opaque payload
success (role 1): u32 payload bytes | opaque payload
error   (role 2): u16 code bytes | u16 message bytes | code UTF-8 | message UTF-8
```

Contract IDs are lowercase route identifiers and operations/error codes are lowercase identifiers. They are non-empty
and at most 128 UTF-8 bytes; exact semantic versions are at most 64 bytes; error messages are non-blank and at most
1,024 UTF-8 bytes. Opaque payloads are limited to 30,000 bytes and complete frames to 32,766 bytes. Decoders reject
malformed UTF-8, unsupported versions or roles, invalid UUID/identifier/semver fields, impossible lengths, role/body
mismatches, and trailing bytes. Encoders and transport boundaries copy mutable byte arrays.

Paper requests time out and explicitly distinguish unavailable transport from a returned canonical success/error frame.
With no Velocity proxy or no online player carrier, transport is a no-op returning unavailable; Paper startup and all
local runtime behavior continue normally.

Velocity endpoints accept only backend-server sources. Accepted requests are marked handled and receive correlated
responses. Unknown channels, invalid data, non-request envelopes, and other sources retain Velocity's prior forwarding
semantics. Bootstrap exposure is restricted to `paperProxyCarrier`, `paperProxyRequest`, and
`velocityRegisterProxyEndpoint` allowlisted capabilities.
