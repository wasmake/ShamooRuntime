# Release candidates

Pushing a semantic-version tag such as `v0.2.0-rc.1` starts the `Release candidate` workflow. Non-semantic `v*` tags
fail before building. The workflow runs the complete Gradle build, deterministic generated-model diff, independent
Paper and Velocity API coverage checks, and both server process integrations. Packaging cannot run unless every
matrix job succeeds.

Packaging checks out the tagged commit, derives `projectVersion` from the tag, and builds separate reproducible Paper
and Velocity JARs. It emits `SHA256SUMS` covering both JARs and the SPDX JSON SBOM, then uses GitHub's OIDC-backed
artifact attestation service for signed build provenance over the JARs, checksums, and SBOM plus artifact-linked SBOM
attestations. The candidate directory is retained as a GitHub Actions artifact for 30 days.

The workflow has no `contents: write` permission and contains no GitHub Release, package-registry, Maven, or plugin
portal publication step. A candidate must be downloaded, verified, and promoted through a separately reviewed manual
release process. Local Phase 12 verification does not push tags, invoke the workflow, create attestations, or publish
anything.
