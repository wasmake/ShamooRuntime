# Security Policy

## Supported versions

Security fixes are provided for the latest released minor line. Pre-release builds are for evaluation and should not
be exposed to untrusted scripts.

## Reporting

Report vulnerabilities privately through GitHub Security Advisories for this repository. Include affected versions,
reproduction steps, impact, and any known mitigation. Do not open a public issue before maintainers have coordinated
disclosure. You should receive an acknowledgement within seven days and a status update within fourteen days.

## In-process runtime boundary

The runtime applies defense-in-depth policy at every runtime-controlled module, builtin, filesystem, and host-callback
boundaries. It removes unrestricted `require` and `process`, disables native builtin resolution, exposes no JVM class
lookup or reflective Java proxy, and denies native filesystem, network, child-process, worker, and addon paths.
The narrow text-file API uses descriptor-relative no-follow traversal when the filesystem provider supports
`SecureDirectoryStream`; it denies filesystem operations rather than falling back to check-then-open path handling
when that primitive is unavailable.

Manifest capability declarations are requests, not grants. Every builtin load and mediated filesystem operation
checks the immutable per-runtime policy again. Builtin aliases are canonicalized; subpaths of denied native modules,
URL/package resolution, absolute and traversal paths, symlinks, and descriptor-swap races fail closed. Network,
workers, child processes, native addons, native filesystem APIs, and module-loader access remain unavailable even when
requested because the in-process embedding cannot mediate all operations securely.

The frozen `host` object has a null prototype and contains only explicitly registered direct callbacks. Callback
conversion accepts scalar data, byte arrays, and acyclic string-keyed lists/maps. Java classes, class loaders,
reflection members, dynamic proxies, Javet values, arbitrary Java service objects, and cyclic object graphs are
rejected before crossing the boundary. Generated platform operations additionally require the matching Paper or
Velocity namespace and protocol metadata on every call.

## Trust boundaries

Script plugins are untrusted relative to host APIs, other plugin generations, and paths outside their own plugin root.
Generated descriptors are build inputs, not runtime authority; the host links only checked-in, independently verified
platform models. Platform adapters, lifecycle code, host callback implementations, the JVM, Javet/V8/Node native
code, and the server process are trusted computing base. Signed release provenance proves which workflow built an
artifact, not that scripts are safely isolated from native-engine vulnerabilities.

This is not a complete security sandbox. Java, Javet, V8, Node, JNI, and plugin code still share one operating-system
process. A native engine vulnerability, denial-of-service condition outside V8's execution guard, or future Node API
that bypasses the controlled boundary can affect the server. Requests for unsupported manifest permissions stay
denied; they are not silently treated as enforced. Run mutually untrusted code in a separately restricted process or
container and obtain independent security review before exposure.
