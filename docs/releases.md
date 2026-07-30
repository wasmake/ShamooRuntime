# Release candidates

The canonical first release-candidate tag is `v0.1.0-rc.1`. Pushing a canonical SemVer tag matching `v*-rc.*` starts
the `Release candidate` workflow. The workflow rejects malformed matching tags and commits that are not ancestors of
`main`. It runs the complete Gradle build, deterministic generated-model diff, independent Paper and Velocity API
coverage checks, and both server process integrations at the exact candidate version. Packaging cannot run unless
every matrix job succeeds.

Packaging checks out the tagged commit and passes the tag without its leading `v` as `projectVersion`; the checked-in
`gradle.properties` remains `0.1.0-SNAPSHOT` for development. The JAR and server logs identify the candidate as
`0.1.0-rc.1`, while Runtime compatibility negotiation intentionally uses its stable `0.1.0` base so descriptors with
the default `^0.1.0` range accept it. The workflow builds separate reproducible Paper and Velocity JARs, emits
`SHA256SUMS` covering both JARs and the SPDX JSON SBOM, and uses GitHub's OIDC-backed artifact attestation service for
build provenance over all four files plus artifact-linked SBOM attestations. The candidate directory remains available
as a GitHub Actions artifact for 30 days.

After attestation, a separate least-privilege job with `contents: write` creates the tag's GitHub prerelease. GitHub CLI
stages every asset on a draft before publishing it, so a public partial release is not exposed. Publication fails if a
release already exists for the tag; published candidate assets are never replaced. The prerelease contains exactly:

- `shamoo-runtime-paper-0.1.0-rc.1.jar`
- `shamoo-runtime-velocity-0.1.0-rc.1.jar`
- `shamoo-runtime.spdx.json`
- `SHA256SUMS`

## Synchronized tag order

Runtime and TypeScript release candidates use the same version, but their tags are repository-specific and must be
pushed in this exact Runtime-first order:

1. Merge the reviewed Runtime release pull request into `main`, update the local `main`, verify its commit, then tag it
   and push `v0.1.0-rc.1`:

   ```bash
   git switch main
   git pull --ff-only origin main
   git tag -a v0.1.0-rc.1 -m "ShamooRuntime v0.1.0-rc.1"
   git push origin refs/tags/v0.1.0-rc.1
   ```

2. Wait for the Runtime `Release candidate` workflow to succeed, then download and verify the Runtime prerelease as
   shown below.
3. Only after Runtime verification succeeds, merge the reviewed ShamooTS release pull request into `main`, update the
   local `main`, and run the corresponding commands from `ShamooTS`:

   ```bash
   git switch main
   git pull --ff-only origin main
   git tag -a v0.1.0-rc.1 -m "ShamooTS v0.1.0-rc.1"
   git push origin refs/tags/v0.1.0-rc.1
   ```

Do not move or reuse either tag. If a tagged commit is wrong, prepare the next synchronized candidate, such as
`v0.1.0-rc.2`, again in Runtime-first order.

## Verify and install

Install GitHub CLI 2.49.0 or newer, authenticate it, and download all assets into a new directory:

```bash
set -euo pipefail
TAG=v0.1.0-rc.1
REPO=wasmake/ShamooRuntime
DEST="shamoo-runtime-$TAG"
mkdir "$DEST"
gh release download "$TAG" --repo "$REPO" --dir "$DEST"
(cd "$DEST" && sha256sum --check SHA256SUMS)
for artifact in "$DEST"/*.jar "$DEST"/*.spdx.json "$DEST"/SHA256SUMS; do
  gh attestation verify "$artifact" \
    --repo "$REPO" \
    --signer-workflow "$REPO/.github/workflows/release-candidate.yml" \
    --source-ref "refs/tags/$TAG"
done
for artifact in "$DEST"/*.jar; do
  gh attestation verify "$artifact" \
    --repo "$REPO" \
    --signer-workflow "$REPO/.github/workflows/release-candidate.yml" \
    --source-ref "refs/tags/$TAG" \
    --predicate-type https://spdx.dev/Document/v2.3
done
```

Stop the target server and install only its matching JAR. Paper and Velocity artifacts are not interchangeable:

```bash
install -m 0644 "$DEST/shamoo-runtime-paper-${TAG#v}.jar" /path/to/paper/plugins/
install -m 0644 "$DEST/shamoo-runtime-velocity-${TAG#v}.jar" /path/to/velocity/plugins/
```

Use only the command for the target server, then restart it and confirm that its startup log reports the expected
Runtime version. The bundled Javet Node native runtime currently supports Linux x86-64 only.

## Promotion and publication

An RC tag and its prerelease are immutable version identities, not stable releases. The workflow refuses to replace an
existing release. Do not clear the prerelease flag or rename/move the tag to promote it. Stable `0.1.0` requires a
separately reviewed `v0.1.0` tag and final-release process; the current RC workflow does not run for that tag.

Paper and Velocity bootstrap JARs remain GitHub release assets. Reusable Java modules are published separately under
`com.shamoof` from `https://shamoof.com/maven`; publication requires the immutable release version and a private
`SHAMOO_MAVEN_TOKEN`. The aggregate `publishLibraries` task publishes only protocol, core, Javet, code-generation,
Paper adapter, and Velocity adapter modules. It excludes bootstraps, process-integration harnesses, and the
version-specific Paper NMS implementation.

Publish only from the clean commit identified by the matching immutable tag:

```bash
VERSION=0.1.0-rc.1
test "$(git describe --tags --exact-match)" = "v$VERSION"
test -z "$(git status --porcelain)"
test -n "${SHAMOO_MAVEN_TOKEN:-}"
./gradlew check -PprojectVersion="$VERSION" --no-daemon
./gradlew publishLibraries -PprojectVersion="$VERSION" --no-daemon
```

The published coordinates are `runtime-protocol`, `runtime-core`, `runtime-javet`,
`runtime-codegen-support`, `platform-paper`, and `platform-velocity` under the `com.shamoof` group. Consumers should
use exact versions. Gradle consumers can resolve them with:

```kotlin
repositories {
    maven("https://shamoof.com/maven")
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    implementation("com.shamoof:platform-paper:0.1.0-rc.1")
}
```

Paper, Velocity, and Netty APIs are server-provided and are not runtime dependencies in Gradle module metadata. Their
Maven POM entries use `provided` scope; Maven consumers compiling against public platform types must declare the
matching APIs as direct `provided` dependencies and must not shade them into plugins. `runtime-javet` currently supports
Linux x86-64 only.
