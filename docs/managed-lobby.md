# Managed lobby bridge

The Paper bootstrap can expose one purpose-built direct Javet host function to one configured script plugin:

```yaml
managed-lobby:
  enabled: false
  owner: shalobby
  data-directory: data
  maximum-pending-actions: 64
```

Only the exact manifest identity in `managed-lobby.owner` receives `host.paperManagedLobby(request)`. The default data
location is `<ShamooRuntime data folder>/data/<owner>` and is rejected if it is beneath `plugins.directory`. It remains
stable across staged artifact replacement. The fully resolved owner directory must not overlap `plugins.directory` in
either direction and is capped at 512 characters for the cross-language response contract. The capability stays disabled
by default. Managed-lobby supports standard Paper 1.21.8 only. Generic Runtime can remain Folia-capable, but enabling this
feature on Folia aborts Java plugin startup before a managed store is created or defaults can be generated or activated.

Each runtime generation owns its bridge, listeners, tasks, menus, scoreboards, selections, and pending work. A staged
generation may load and validate files, but it cannot activate native behavior while
`PluginRuntimeContext.invocations().snapshot().accepting()` is false. A one-tick native poll activates it only after
Runtime opens invocation admission. The prior coordinator-owned bridge remains native-active while it drains. Activation
registers provisionally, applies live policy and schedules player setup, then commits ownership; candidate rollback keeps
the prior admitted generation active and reapplies its world policy. The standby retains its complete prepared shared-store
snapshot and rechecks all eight files on its bounded file executor immediately before scheduling global handoff. The global
callback uses only the store's in-memory version fence. If the snapshot is stale, one bounded refresh at a time reads,
parses, and natively preflights the latest snapshot while the prior bridge remains active. A failed snapshot is not
preflighted again until the one-second backoff expires and its content changes.

Only the first managed-bridge activation during the Java Runtime plugin's lifetime may apply `join.reset` to players who
are already online. A replacement remains hot even if the old bridge closes before candidate admission, leaving a brief
coordinator gap.
Reloads and successful handoffs remove the prior generation's PDC artifacts, then install the current hotbar, sidebar,
and visibility policy without clearing ordinary inventory, potion, experience, flight, or staff state.

## Files

The bridge manages exactly these eight UTF-8 YAML files:

- `config.yml`: managed worlds, join behavior and welcome references, void rescue, world enforcement, visibility,
  transfer cooldown, and protection.
- `messages.yml`: named MiniMessage messages, titles, sounds, and data-free particles.
- `items.yml`: managed hotbar items with PDC IDs, slots, MiniMessage name/lore, cooldown, and native action.
- `menus.yml`: protected inventory menus and native slot actions.
- `scoreboard.yml`: one per-player sidebar, optional animated title frames, lines, and a separate update interval.
- `servers.yml`: server IDs with `enabled`, BungeeCord `target`, and MiniMessage `display-name` fields.
- `spawn.yml`: exactly one optional global spawn.
- `portals.yml`: bounded world portals and native destination/action settings.

`effects.yml` is not part of the contract and is neither ensured nor read. Fresh files use Runtime's embedded lobby catalog:
polished Spanish messages and welcome effects, five hotbar items, four menus, six enabled server definitions, an animated
sidebar, three disabled example portals, and one unconfigured spawn. Existing files are never replaced by `ensure`.
Operators still need to confirm managed world names and configure spawn and proxy server targets. Updating Runtime does
not migrate existing files; copy or merge changed defaults manually when adopting a newer ShaLobby catalog.

Spawn is explicitly discriminated:

```yaml
spawn: { configured: false }
```

When `configured` is `true`, `world`, `x`, `y`, `z`, `yaw`, and `pitch` are all required. Spawn is never a list and
must reference a managed world.

Enabled `servers.yml` entries form the complete transfer allowlist. `connect` actions and server-type portal destinations
reference the server ID; the native bridge sends its configured `target`. Disabled or unknown IDs fail strict reference
validation. `config.yml` retains only the transfer cooldown.

SnakeYAML's safe constructor has duplicate-key, alias, code-point, and nesting limits. Every object has an explicit key
allowlist, values and collections are bounded, references are checked, and coordinates must be finite. Portal bounds must
also use integer block coordinates; lookup floors the player's coordinates and treats both selected endpoint blocks as
inside the portal. Files are capped at 1 MiB. Writes use a same-directory temporary file, file `fsync`, atomic replacement, directory `fsync`, and an
atomically written `<file>.bak` containing the prior bytes. Overlapping generations share one store and use complete
snapshots; changes observed during verification reject stale writes and native applies. Runtime stages replacement bytes
before the final comparison to narrow its compare-to-rename window, but a non-cooperating filesystem editor cannot join
an atomic in-process CAS and can still race between file-executor verification and the scheduled global callback. Operators
must not edit these files concurrently with `write`, `reload`, `setspawn`, portal mutation, or generation handoff.
Once a target rename commits, Runtime invalidates its in-memory version before directory `fsync`; even a reported durability
failure therefore fences every previously verified native apply.

## Requests

The host function accepts exactly one data object and always returns a JavaScript Promise for a data map, including
status, validation, overload, and closed-generation responses. Every result has `ok` and `state`; failures include a
bounded `error`. Normal failure states are `invalid`, `unknown`, `unavailable`, `overloaded`, and `error`.

```javascript
await host.paperManagedLobby({ operation: "ensure" });
await host.paperManagedLobby({ operation: "read" });
await host.paperManagedLobby({ operation: "read", file: "servers.yml" });
await host.paperManagedLobby({
  operation: "write", file: "items.yml", content: "items: []\n", reload: true
});
await host.paperManagedLobby({ operation: "reload" });
await host.paperManagedLobby({ operation: "status" });
```

A successful `reload` includes bounded `messagesContent` containing the exact `messages.yml` text from the same prepared
snapshot accepted for native activation. Callers can atomically correlate their command-message catalog with that result.
The `servers` count in reload and status results is the total number of configured `servers.yml` entries, including
disabled entries; only enabled entries belong to the transfer allowlist.

Host result `state` values are protocol labels and are not automatically used as `messages.yml` lookup keys. Native item
and portal cooldown feedback explicitly uses `item-cooldown` and `portal-cooldown` with `%seconds%`. Native portal-wand
block selection explicitly uses `portal-pos1` or `portal-pos2` with `%world%`, `%x%`, `%y%`, and `%z%`. The execute API
retains its existing `portal-pos1`/`portal-pos2` result-state labels independently of that explicit native lookup.

`write` defaults to `reload: true` and parses and natively validates the complete candidate before committing.
`reload: false` permits an intentional multi-file transaction; active native configuration remains unchanged until a
later complete reload passes. A failed parse, registry lookup, loaded-world check, game-rule check, or stale snapshot
leaves the previous active configuration intact. Native preflight renders every MiniMessage value with the production
parser, builds representative managed item and menu metadata plus sidebar components, resolves registries and loaded
worlds, validates game-rule values, and constructs the immutable portal index. Paper listener registration,
repeating-task scheduling, and live world/player mutation remain irreducible post-persistence scheduler effects because
Paper cannot atomically commit them with filesystem replacement. Candidate tasks and listeners are staged while old tasks,
menus, and presentation state remain owned by the active configuration. Runtime captures every candidate-touched world
time, weather value, and game rule; activation failure restores them and the prior in-memory state before old activity
resumes. Old tasks and destructive menu/world cleanup are retired only after native activation commits.

Optional `read.file` and `write.reload` fields must be omitted rather than sent as null. Omission reads all files and makes
write reload default to `true`.

General execute actions are `setspawn`, `spawn`, `items`, `menu`, and `visibility`. Player arguments are canonical UUIDs.
The targeted player must be online and in a managed world.

Portal administration execute actions are:

- `portal-wand`: gives an authorized administrator a managed selection wand.
- `portal-pos1` and `portal-pos2`: set a selection position from the administrator's current block.
- Wand left/right block clicks set position 1/2 synchronously and cancel the click.
- `portal-create`: creates a portal from that administrator's two positions.
- `portal-remove`, `portal-list`, `portal-info`, `portal-enable`, `portal-disable`, and `portal-destination`.
- `portal-visualize`: toggles bounded visualization for an authorized administrator.

`portal-create` accepts an optional `destination` server ID. When omitted, the portal retains a native `none` action.
Omitted `visualize` defaults to `false`, matching the YAML portal parser.
All optional `portal-create` values must be omitted rather than sent as null. `portal-destination` uses a discriminated
request instead of the legacy request field:

```javascript
await host.paperManagedLobby({
  operation: "execute", action: "portal-destination", player, id: "portal-survival",
  type: "server", target: "survival"
});
await host.paperManagedLobby({
  operation: "execute", action: "portal-destination", player, id: "portal-survival",
  type: "spawn"
});
await host.paperManagedLobby({
  operation: "execute", action: "portal-destination", player, id: "portal-survival",
  type: "menu", target: "game-selector"
});
```

`type` is exactly `server`, `spawn`, or `menu`. `target` is required for server/menu and must be absent for
spawn. Server targets must be enabled in `servers.yml`; menu targets must exist in `menus.yml`. Server destinations
persist CONNECT plus the matching legacy `destination`; spawn and menu persist SPAWN/MENU and clear the legacy field.
Successful results include the complete persisted portal map and a feedback message.

Portal results include useful portal/list/position maps and Spanish feedback text. Persisted portals include `enabled`,
`permission`, `priority`, `cooldown-ms`, `destination`, native `action`, and `visualize`, plus world and min/max bounds.
Portal IDs and all references are strictly validated. Every destructive portal operation (`portal-create`,
`portal-remove`, `portal-enable`, `portal-disable`, and `portal-destination`) requires a `player` UUID whose online player
is in a managed world and has the configured bypass/editor permission. `portal-create` rejects an existing ID rather than
replacing it. Read-only `portal-list` and `portal-info` do not.

## Native Behavior

Lobby policy applies only in worlds listed by `config.yml.worlds`. Players and entities in unrelated worlds are left
alone. A configured join teleport performs player setup only after the asynchronous teleport succeeds. Respawn handling
applies when either the death/current world or the vanilla destination is managed, and a configured spawn replaces that
destination. Movement into a managed world applies the configured reset policy; movement out removes managed presentation
and immediately refreshes remaining viewers. Cancelled movement does not alter portal occupancy or world-transition state.
`PlayerTeleportEvent` is handled on its separate handler list after cancellation decisions: entry setup runs one entity
scheduler tick later so the player is in the destination world, while exit cleanup runs synchronously before viewers are
refreshed. Teleports clear portal occupancy but never invoke movement-portal lookup or actions.

Protection is currently deliberately all-or-nothing:

```yaml
protection:
  enabled: true
  bypass-permission: lobby.protection.bypass
```

The bypass applies to player-caused restrictions and portal editing authorization. Environmental changes in managed
worlds remain protected. One native `EntityDamageEvent` handler cancels every damage cause only when the damaged entity
is a non-bypassing player in a managed world; unrelated players and non-player entities are never made globally
invulnerable. Food and exhaustion follow the same player scope.

Native protection covers block mutation, farmland physical interaction, inventory/drop/pickup/swap, buckets, armor
stands, entity and hanging manipulation, leash/shear actions, projectiles, explosions and TNT priming, cauldron and
fertilization and fluid-level changes, arrow pickup, portal creation/use, vehicle damage/destruction/collision, structure
growth, hostile targeting, and configured weather. Structure growth and portal creation honor the configured bypass when
Paper identifies a player actor; actorless and non-player environmental causes remain protected. Hopper-style inventory
movement and inventory pickup are protected by the managed location even when no player actor exists. Cancellation happens
synchronously in the originating Paper event.

Join reset covers Adventure mode, health, hunger, effects, inventory/armor/offhand, experience, velocity, fire/fall
state, and flight. After reset, configured welcome message/title/sound/particle references are rendered. Item and lore
components are MiniMessage-rendered with italic decoration explicitly disabled.

Sidebars use a private scoreboard per player, stable unique entries for duplicate rendered lines, and only update title
or line components that changed. Title frames advance on `interval-ticks`, independently from low-frequency world/item
enforcement. Built-in placeholders are `%player%`, `%online%`, `%world%`, `%x%`, `%y%`, `%z%`, `%ping%`, and
`%visibility%`. While enabled, an update reclaims the managed scoreboard if another plugin replaced it; cleanup later
restores the scoreboard observed immediately before the latest reclaim.

Managed hotbar items, menu items, and portal wands carry a generation PDC tag. They remain immovable independently of
general protection and bypass permissions. World exit, configuration removal, normal handoff, rollback, and shutdown
remove only the owning generation's artifacts and restore the scoreboard that generation replaced. A managed menu holder
from any Runtime generation remains click/drag protected after its live session is invalidated, including for bypass
players; only the active holder generation can resolve a session and execute its action. Hotbar item actions run only for
main-hand right-click-air or right-click-block interactions; left clicks remain inert except for portal wand selection.

Portal lookup is immutable and indexed by world and chunk. Disabled portals are excluded. Movement handlers perform
only in-memory scope, void, entry, permission, priority, and cooldown checks. Actions run later on the entity scheduler,
so movement events perform no disk or plugin-message network work. Before executing, the deferred callback rechecks bridge
ownership, player online/managed state, the current enabled portal and unchanged action, permission, occupancy, actual
containment, and highest-priority trigger. Occupancy includes a unique transition token, so leaving and re-entering the same
portal invalidates callbacks from the prior entry even when cooldown is zero. Portal and item cooldowns begin only after the
native action is accepted; rejected or unavailable actions do not consume them. Cooldown feedback rounds remaining time up
to whole seconds.

## Security And Limits

The bridge does not support arbitrary console commands, reflective Bukkit access, multiple/named spawns, external
placeholder providers, custom particle data, proxy pings, or fabricated server status. A BungeeCord transfer indicates
only that an allowlisted request was sent; proxy acceptance remains unknown. Sounds and particles must exist in Paper's
registries, and the configured spawn world must be loaded during activation.

The pending file/global/entity queue is bounded by `maximum-pending-actions`. Native event cancellation never waits for
JavaScript. Closing a script generation fences queued callbacks and settles all outstanding host promises; script
generation hot replacement remains supported while the Java Runtime plugin stays enabled. Live disable, reload, or
replacement of the ShamooRuntime Java plugin is unsupported for managed-lobby; use a full standard Paper server stop so
shutdown owns deterministic native cleanup. Isolate restrictions and owner gating are defense in depth inside one JVM,
not an operating-system boundary.

Direct custom host calls take an invocation lease only while admission is open, and asynchronous results retain it until
settlement. If admission closes between the optimistic snapshot and `admit()`, the host returns a rejected Promise rather
than throwing synchronously. Admission-closed lifecycle hooks remain callable without a lease because the current
`PluginRuntimeContext` does not expose whether a direct call is from initialization or unload; rejecting post-drain calls
would also reject legitimate unload cleanup. Runtime passes the captured lease presence to the managed-lobby binding as a
Java-only invocation context; it is not inserted into the JavaScript request. A leased mutation may finish persistence and
active reload after drain starts. An unleased mutation from an active or previously activated generation is rejected before
file work, while an unleased initial reload on a not-yet-active candidate may still prepare standby configuration. Read,
status, and ensure remain lifecycle-safe.
