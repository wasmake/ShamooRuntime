# Security Policy

## Supported versions

Security fixes are provided for the latest released minor line. Pre-release builds are for evaluation and should not
be exposed to untrusted scripts.

## Reporting

Report vulnerabilities privately through GitHub Security Advisories for this repository. Include affected versions,
reproduction steps, impact, and any known mitigation. Do not open a public issue before maintainers have coordinated
disclosure. You should receive an acknowledgement within seven days and a status update within fourteen days.

## In-process runtime boundary

Phase 5 applies defense-in-depth policy at runtime-controlled module, builtin, filesystem, and host-callback
boundaries. It removes unrestricted `require` and `process`, disables native builtin resolution, exposes no JVM class
lookup or reflective Java proxy, and denies native filesystem, network, child-process, worker, and addon paths.
The narrow text-file API uses descriptor-relative no-follow traversal when the filesystem provider supports
`SecureDirectoryStream`; it denies filesystem operations rather than falling back to check-then-open path handling
when that primitive is unavailable.

This is not a complete security sandbox. Java, Javet, V8, Node, JNI, and plugin code still share one operating-system
process. A native engine vulnerability, denial-of-service condition outside V8's execution guard, or future Node API
that bypasses the controlled boundary can affect the server. Requests for unsupported manifest permissions stay
denied; they are not silently treated as enforced. Run mutually untrusted code in a separately restricted process or
container and obtain independent security review before exposure.
