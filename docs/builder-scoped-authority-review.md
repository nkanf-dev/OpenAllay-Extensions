# Builder scoped authority review

## Root cause

The previous `BuilderRuntime.install` checked `unrestrictedJavascript`, and
`building.open` reached the native runtime through `Java.type`. Calling Builder
therefore required granting the Agent the whole JVM. The native adapter itself
already had the necessary local topology, exact player/session identity,
owner-thread, loaded-chunk and cancellation checks.

## New boundary

- `openallay_builder:native` is one immutable, typed method whitelist.
- Opening returns an opaque, invocation-local session ID. No Java object enters
  JavaScript. Arguments and results are detached JSON values.
- Opening, reads, scans and status do not require world-write authority.
- `write`, `writeRegion`, `updateConnections`, `syncPhysics` and `undo` require
  the independent `openallay_builder:world_write` grant. The trusted method bridge,
  Builder handler and actual native effect boundary check it.
- Package installation and Agent JVM access do not grant world-write authority.
  Server-origin requests have no local write grants.
- Native backend capture is lazy. Installing Builder does not make unrelated
  JavaScript queue client/server work. Read-only access does not initialize the
  Minecraft SavedData world UUID. Historical operation listing uses only an
  existing UUID; first authorized writing/undo creates it on the server owner.
- Reviewed domain failure codes cross the bridge with fixed safe messages, not
  native exception text, causes or stacks. Permission failure has its own code.
- Trusted native waiting does not consume the pure-JavaScript interpreter budget.
  The core still owns cancellation, source, result and workspace budgets.

## Performance preservation

The existing terrain scans/probes, sparse capture, native codecs, journals,
`terrain.js` and `presets.js` algorithms are unchanged. `BuilderSession` only
routes operation listing through a read-existing identity accessor; its capture,
write, physics and undo algorithms are unchanged. The existing
read/write quanta and 4 ms native scheduling deadline are unchanged. Generated
geometry still sends one complete final-image region plan, not per-cell bridge
calls or a new JavaScript transport quantum. The large detached-plan contract
expects 10,000 cells and twelve backend actions.

## Verification

Builder 0.2.0 release-metadata verification passed on 2026-10-01 using the
checked-in Gradle wrapper and the actual OpenAllay 0.3.0 core JAR implementing
public Extension API 0.2.2:

```text
/Users/nkanf/projs/OpenAllay/gradlew
  -p /tmp/openallay-builder-authority-20261001/extensions/minecraft-builder
  -PopenallayCommonJar=/tmp/openallay-context-overflow-20261001/common/build/libs/openallay-common-26.2-0.3.0.jar
  -Dorg.gradle.jvmargs=-Xmx1536m --max-workers=1
  :common:test :fabric:build :neoforge:build :verifyLoaderPackages
```

- Common tests: **221 passed**, zero failures, errors or skipped tests.
- Restricted native authority contracts: **13 passed**. They use the actual public
  Extension registry and restricted Rhino with `unrestricted=false`, not an
  unrestricted JavaScript fixture.
- SavedData identity tests: **5 passed**, including the two new get-only tests.
- Packaged Skill/module contracts: **4 passed**, including exact documented examples.
- Existing performance contracts: **12 passed**. The frozen baseline was not changed.
- Fabric and NeoForge builds and loader package verification passed.
- Static manifest checks and `git diff --check` passed.

The restricted-native contracts cover reads with `Java` undefined, default-off
writes, explicit Builder grants without JVM access, server-origin rejection,
frozen request grants, opaque-session ownership, revoked scopes, lazy capture,
trusted native waiting longer than two seconds, a later infinite-loop timeout,
and complete 100x100 plan transport. The 10,000-cell plan retains twelve detached
backend actions rather than per-cell dispatch. These are deterministic contract
checks, not live-world throughput or visual-quality claims.

The first full run had one incorrect test expectation: closed JSON transport
rejected a function argument with `javascript_result_invalid` before the host
scalar type check. The test now asserts that exact public error code. No runtime
permission, transport or execution budget was relaxed to make it pass.

Core JAR SHA-256:
`4b4ec127cbb7c94f1de2989cb396ddb04196bcfcc1fbe3375d9b82e0a2b13cfb`.

Final log: `/tmp/openallay-builder-0.2.0-core-0.3.0-20261001.log`.


The complete gate was rerun against the actual product 0.3.0 JAR with Builder
0.2.0 metadata after the earlier integrated development-artifact gates. Common
production and test compilation, all 221 common tests, both loader compilation
and package/resource/JAR tasks executed. Package verification checked the actual
embedded version 0.2.0, product ranges and API range. This verifies the release
metadata and final runtime classpath, not just an earlier API-only JAR.

No graphical game, live model provider or secret configuration was accessed.
No commit or push was made. Generated build output remains outside the change.

## Publication dependency

Release targets are OpenAllay product **0.3.0**, public Extension API **0.2.2** and
independent Builder **0.2.0**. Builder loader metadata accepts product
`[0.3.0,0.4)`; its package manifest and descriptor accept API `[0.2.2,0.3)`.

The exact core source lock version fields now name 0.3.0 and API 0.2.2, but its
revision is still the old published hash pending parent replacement. This is an
incomplete release-preparation lock, not a claim that the old revision has the
new API or product version. Do not run source-lock CI or publish that lock until
the parent fills the final real published core commit.

The successful gate above used the actual 0.3.0 product JAR and Builder 0.2.0
package metadata. No permission or performance source changed for that metadata
transition. The parent will publish the final core commit, fill the real source
lock, and publish the independent Builder source before updating core's exact
Builder pin. The local JAR checksums below are verified build facts; they do not
claim that public release asset URLs already exist.

## Final artifact facts

The final verified development core JAR was built from the complete integrated
core worktree, which implements public Extension API 0.2.2 and the final runtime,
context, tokenizer and result-view changes. Its source changes had not yet been
published when this gate ran. This is a verified development-artifact claim, not
a claim that the previous published source lock implements the new API.

- Core JAR modification time (UTC): `2026-10-01T14:15:50.932024+00:00`.
- Core JAR size: `3387503` bytes.
- Core JAR SHA-256: `4b4ec127cbb7c94f1de2989cb396ddb04196bcfcc1fbe3375d9b82e0a2b13cfb`.
- Fabric Builder JAR: `210974` bytes; SHA-256 `811e7f334c28fdad9ffcd068602969d3090187dd468adfa2d24767eeefa4a47d`.
- Neoforge Builder JAR: `209986` bytes; SHA-256 `277627200ddfcf2963ee44b6ec9fd3bb89bf690b67f967ba3434a164a9908c76`.

Both embedded package manifests declare Builder `0.2.0`, Extension API
`[0.2.2,0.3)`, and only the
advisory `openallay_builder:world_write` requirement. Fabric declares product
`>=0.3.0 <0.4.0`; NeoForge declares product `[0.3.0,0.4.0)`. Restricted-native tests
synthetically grant or deny that exact Extension scope. They do not change any
user runtime configuration, grant real player-world authority or launch a game.
The parent will update the exact source lock to the final published core commit.
