# Runtime Protocol

## Version boundaries

The shared wire protocol is version `1.0` in Java (`ProtocolVersion.CURRENT`) and version `1` in TypeScript
(`RUNTIME_PROTOCOL_VERSION`). Both represent protocol major 1. A runtime only admits a manifest when its protocol has
the same major as `ProtocolVersion.CURRENT` and a minor greater than or equal to the current minor. Manifest format
versioning is independent and appears only at `shamoo.manifest`; this implementation accepts exactly `2`.

## Plugin artifact

A plugin candidate is one directory containing exactly these three direct regular files:

```text
index.js
index.js.map
shamoo-plugin.json
```

Directories, symbolic links, special files, missing files, and any extra files are rejected. `index.js` is always an
ES module. `index.js.map` is mandatory and must be a source-map v3 document. There is no configurable entrypoint and no
separate compiler metadata file. `PluginArtifactProtocol` owns the canonical names used by discovery and execution.

## Canonical manifest v2

The canonical schema is packaged at
`runtime-protocol/src/main/resources/dev/shamoo/runtime/protocol/plugin-manifest-v2.schema.json`. `ManifestCodec`
emits fields in the order shown below and sorts dependency map keys. Input object order is insignificant. Unknown
fields, duplicate JSON keys, trailing JSON values, omitted required fields, null values, and scalar coercion are
rejected.

```json
{
  "name": "identity",
  "displayName": "Shamoo Identity",
  "version": "1.0.0",
  "shamoo": { "api": "^1.0.0", "runtime": "^1.0.0", "manifest": 2 },
  "platforms": {
    "paper": {
      "enabled": true,
      "minecraft": "1.21.x",
      "paperApi": "1.21.x",
      "nms": false,
      "packets": false
    },
    "velocity": { "enabled": true, "velocityApi": "3.x" }
  },
  "dependencies": { "required": {}, "optional": {}, "loadBefore": [], "loadAfter": [] },
  "node": {
    "builtins": ["node:buffer"],
    "filesystem": { "read": ["./"], "write": ["./data"] },
    "network": false,
    "workers": false,
    "childProcess": false,
    "nativeAddons": false
  },
  "reload": { "watch": true, "debounceMs": 500, "preserveState": true },
  "compiler": {
    "version": "2.0.0",
    "components": [],
    "modules": [],
    "communication": { "services": [], "events": [], "consumers": [] }
  }
}
```

Both target objects are required and at least one must be enabled. Both may be enabled because `index.js` is a
universal bundle. An enabled Paper target requires `minecraft`, `paperApi`, `nms`, and `packets`; an enabled Velocity
target requires `velocityApi`. A disabled target has exactly `{ "enabled": false }` and no target-specific fields.
Paper `nms` and `packets` are the only authorization source for those operations.

The required `compiler` object contains exactly `version`, `components`, `modules`, and `communication`; communication
contains required `services`, `events`, and `consumers` arrays. It does not contain `formatVersion`, `packageName`,
`permissions`, `entrypoints`, or `sourceMaps`. Component, method, injection token, module, source location, service,
event, and consumer records retain strict required-field, unknown-field, enum, duplicate, and semantic-version
validation. Component platforms are `common`, `paper`, or `velocity`, and a platform-specific component requires that
target to be enabled. Plugin identity comes from root `name`; Node authorization comes from root `node`.

Filesystem policy paths are plugin-root relative. Forms such as `./`, `./data`, `./config`, and `./cache` are valid;
absolute paths, drive paths, backslashes, NUL, empty segments, and parent traversal are invalid. Plugin and dependency
IDs use lowercase ASCII letters, digits, dots, underscores, and hyphens, begin with a letter, and are limited to 64
characters. The canonical Java schema and validator also limit a manifest to 1,048,576 UTF-8 bytes, non-blank text
and SemVer expressions to 256 Unicode characters, relative paths to 512 characters, and each dependency map or policy
list to 256 entries. Dependency maps must be disjoint, as must the two ordering lists. Lists reject duplicate entries.

Versions use strict SemVer 2.0 syntax, including optional prerelease and build metadata; leading `v` and leading
zeroes are rejected. Constraints use strictly validated NPM-compatible ranges interpreted by semver4j 6, including
comparators, hyphen ranges, wildcards such as `1.21.x` and `3.x`, tilde, caret, and `||`. Unknown or malformed tokens
are rejected rather than ignored. Display names and ranges must contain at least one non-whitespace character. The
schema uses `semver` and `semver-range` format annotations; strict SemVer is also expressed as a pattern, while complete
range validation remains a runtime responsibility.

## Diagnostics

`RuntimeProtocolException` exposes immutable `ProtocolDiagnostic` values containing a stable code, JSON-oriented
path, and actionable message. `ManifestParseException` represents malformed JSON or shape, while
`ManifestValidationException` represents decoded values violating manifest invariants. Serialization failures use
`ManifestSerializationException`.

## Compatibility

`CompatibilityInput` identifies the selected platform and supplies Minecraft and Paper API versions for Paper or a
Velocity API version for Velocity, plus runtime, API, protocol, and host capability facts. Negotiation independently
checks each applicable range and every requested Node capability, returning all `IncompatibilityReason` values in
stable order.

`runtime-core` exposes `ManifestValidator.parseCompatible` as the admission boundary. This phase does not implement
plugin lifecycle, dependency resolution, policy enforcement, state preservation, file watching, or reload behavior.
