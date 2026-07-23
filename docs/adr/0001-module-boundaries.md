# ADR 0001: Separate Runtime, Platform, and Bootstrap Modules

- Status: Accepted
- Date: 2026-07-23

## Context

Paper and Velocity have different plugin lifecycles and schedulers. The V8 implementation has native resources and
must not leak platform classes into reusable runtime contracts.

## Decision

Keep protocol records in `runtime-protocol`, platform-neutral contracts in `runtime-core`, and V8 implementation in
`runtime-javet`. Each `platform-*` module only adapts host services. Each `bootstrap-*` module is an independent
composition root and owns runtime startup and shutdown. Integration support is split by platform.

## Consequences

Core code can be tested without a game server, platform artifacts cannot accidentally load the other platform API,
and native lifecycle ownership is explicit. Distribution assembly must intentionally compose dependencies later.
