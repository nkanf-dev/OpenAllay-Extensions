# Builder 0.2.0 release preparation

## Release contract

- OpenAllay product: `0.3.0`.
- Public Extension API: `0.2.2`.
- Independent Minecraft Builder: `0.2.0`.
- Minecraft: `26.2`; Java: `25`; loaders: Fabric and NeoForge, client only.
- Builder product compatibility: `[0.3.0,0.4)`.
- Builder public API compatibility: `[0.2.2,0.3)`.
- Native world writes still require the independent, default-off
  `openallay_builder:world_write` grant. Agent JVM access is not required.

Version changes do not change Builder algorithms, native permission checks,
source/result budgets or journal formats.

## Pending exact source pin

`extensions/minecraft-builder/openallay-source.lock.json` names product 0.3.0 and
API 0.2.2. Its `revision` still holds the old published core hash until the parent
publishes the final core source commit and replaces it. This incomplete lock is
not a claim that the old revision supports the new versions. Do not publish it
or use the source-lock quality workflow before that replacement.

The final 221-test gate passed against the actual product 0.3.0 JAR and Builder
0.2.0 metadata. Both loaders and package verification passed. The core JAR
SHA-256 is `4b4ec127cbb7c94f1de2989cb396ddb04196bcfcc1fbe3375d9b82e0a2b13cfb`.
See `builder-scoped-authority-review.md` for the command, log, actual JAR
fingerprints and exact test boundary. The source-lock CI gate remains pending
until the parent replaces the old revision with the real published source hash.

## Existing automation and exact remaining work

`.github/workflows/builder-quality.yml` checks out the exact core revision in the
lock, builds its common JAR, and runs `:common:test :fabric:build :neoforge:build
verifyLoaderPackages`. It does not publish Builder release assets. The existing
`release-example.yml` publishes only `hello-v*`; it must not be mistaken for a
Builder publisher.

The parent-owned release flow is:

1. Commit, push and verify core source implementing product 0.3.0 / API 0.2.2.
2. Put that actual published full commit hash in the Builder source lock.
3. Commit and push the independent Builder source and exact lock in reviewed
   batches. Create and push tag `builder-v0.2.0` only for the verified source.
4. Build the tag against the pinned core artifact with the checked-in core wrapper:

   ```sh
   /path/to/OpenAllay/gradlew -p /path/to/OpenAllay :common:jar
   /path/to/OpenAllay/gradlew -p /path/to/OpenAllay-Extensions/extensions/minecraft-builder \
     -PopenallayCommonJar=/path/to/OpenAllay/common/build/libs/openallay-common-26.2-0.3.0.jar \
     :common:test :fabric:build :neoforge:build verifyLoaderPackages
   ```

5. Compute SHA-256 from the actual two `0.2.0` loader JARs after verification.
   Publish those exact bytes and their `SHA256SUMS` file with a tag-triggered
   Builder workflow or the GitHub CLI. The tag-triggered workflow should reuse
   the lock read/checkout/build sequence in `builder-quality.yml`, then run
   `gh release create builder-v0.2.0 --verify-tag` with the two verified JARs and
   checksum file. Publishing requires `contents: write`; quality remains read-only.
6. Download the published assets and verify the bytes and embedded manifests.
   Use the returned actual HTTPS release asset URLs and exact SHA-256 values.
7. Add `openallay:builder` version `0.2.0` to `catalog.json` only then. Keep the
   existing Hello entry unchanged. Required Builder catalog fields are:
   identity/name/provider/summary, Minecraft `[26.2,26.3)`, API `[0.2.2,0.3)`,
   both loader artifacts with actual URLs and checksums, `modIds` containing only
   `openallay_builder`, a public tag source location, and advisory requirements
   containing only `openallay_builder:world_write`.
8. Refresh `generatedAt`, run `node scripts/build-catalog.mjs`, and verify each
   published package with `scripts/validate-package-manifest.mjs`. Commit and push
   the catalog as its own reviewed batch. Update core's external Builder source
   pin only after the independent source commit is actually published.

No 0.2.0 asset URL or checksum is stored in the catalog by this preparation.
The pre-release 0.1.0 development JAR fingerprints are historical test evidence,
not release checksums. No tag, asset, catalog entry or release is created here.
