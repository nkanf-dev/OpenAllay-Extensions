# Builder Terrain Batching Implementation Plan

> **For agentic workers:** Execute this approved plan inline with review checkpoints. The root agent owns all builds and commits.

**Goal:** Remove per-block game-thread round trips from Builder terrain scans without changing their range or classification.

**Architecture:** The existing JS module supplies immutable ground and vegetation ID sets to an internal native scan command. A Java cursor scans columns in bounded owner actions and emits detached results. WORLD_SURFACE only skips proven vanilla air above a column; custom native-air IDs disable that optimization for the bound session.

**Tech Stack:** Java 25, Minecraft 26.2, Gson, ES5 CommonJS, restricted Rhino fixture, JUnit 5.

---

### Task 1: Detached scan engine and tests
- [x] Add `extensions/minecraft-builder/src/shared/java/dev/openallay/builder/TerrainScan.java`: request parsing, increasing-X/increasing-Z indexing, exact predicates, resumable cell/column quantum, complete missing-column results.
- [x] Add `extensions/minecraft-builder/common/src/test/java/dev/openallay/builder/TerrainScanTest.java`: 49x49 indexing, owner-call reduction, full/custom bounds, custom ground, liquids, persistent/stripped vegetation, missing/error reads, cancellation.
- [x] Keep all inputs/results detached; no native BlockState or JS callback escapes the adapter.

### Task 2: Native bridge integration
- [x] Extend `BuilderBackend.java` with owner-only terrain-state reads and conservative column upper-bound methods. Detached fake backends retain full-height defaults.
- [x] Add `BuilderSession.scanColumns(String)` using the existing worker/bridge checks and 256 scheduling quantum. Emit sliced read evidence; do not begin an operation or write journals.
- [x] Add `NativeBlockCodec` state-only detached encoding, with live missing-BE validation but without copying BE payloads.
- [x] In `NativeBinding.java`, cache whether ALL possible registered native-air states have vanilla air IDs, then use live WORLD_SURFACE only when that implication is valid. Validate loaded chunk/height/border before querying it. Otherwise scan from requested maxY-1.

### Task 3: JS dispatch and semantic comparison
- [x] In `building.js`, pass the private required `util.scanColumns` hook that flushes pending assignments and calls backend.scanColumns with JSON; public API remains unchanged.
- [x] In `terrain.js`, derive the same ground/vegetation lists already used by the legacy predicate and use that hook for scan_ground/scan_terrain. All backends implement the batch contract; there is no production per-voxel fallback.
- [x] Extend Rhino fixture/tests to compare the native request/results with the legacy JS baseline across custom dimension bounds, invalid/missing cells, water, chest, leaves, persistent and custom ground, and the 2401-column grid.

### Task 4: Documentation and root review
- [x] Update package README and terrain reference with cooperative native reads, conservative heightmap use, full-range fallback and non-atomic multi-slice semantics.
- [x] Root and an independent reviewer inspected the source, including checkpoint uncertainty and derived-edit races.
- [x] After the manual GUI exited, root ran all common tests against the current core JAR: 155 tests, zero failures/errors/skips.
- [x] The worker performed source edits only; root owned native gates and publication.

### Task 5: Approved construction/journal refactor
- [x] Remove JS's 256-assignment transport flush; geometry sends one write-region plan per contiguous segment, separated by explicit read, bed/door and connection-repair barriers. Native owner scheduling remains 256.
- [x] Capture templates through readRegion with legacy increasing-X/Y/Z template order, native states and per-cell SNBT.
- [x] Batch undo capture/preview and compare/apply. Preserve initial/apply races as conflicts and known-unstarted withdrawals.
- [x] Replace quadratic snapshot rewriting with current-shape base + strictly sequenced forced atomic delta records + terminal compact checkpoint.
- [x] Preserve intent-before-write, outcome-before-next-quantum, retouch history and recovery without replay. Poison admission on uncertain delta publication failures.
- [x] Add publication/byte growth tests, crash cuts, missing/corrupt sequence tests, cleanup-crash tests and strict malformed-current-shape tests.
- [x] Add 2401-write and undo owner-dispatch tests, exact journal images, conflict races and partial evidence tests.
- [x] Root ran complete common tests, Fabric/NeoForge builds and `verifyLoaderPackages` serially. See the verification record below.

### Task 6: Latest-only artifact shape and derived terrain planning
- [x] Remove internal journal/template/world-identity version fields/constants/branches. Preserve external Minecraft gameVersion/dataVersion and Extension ABI/package versions.
- [x] Poison uncertain terminal checkpoint publication and reject further admission on the same live handle.
- [x] Require batch backend methods; remove production per-voxel/automatic rollback compatibility fallbacks.
- [x] Batch flatten/clear per needed column, release read-only caches after deriving edits, and reuse native ground scan for paths/blending.
- [x] Carry full observed expectedBefore into conditional edits and compare before preflight.
- [x] Add complete 49x49 flatten/clear/path transport test, race regression and frozen test-only legacy predicate oracle.
- [x] Narrow contiguous-geometry segment contract around deliberate read/bed/door/connection barriers.

## Root verification record

2026-10-01, Java 25 and Minecraft 26.2:

```bash
./gradlew -PopenallayVersion=0.2.4 -PopenallayArtifactsDir=/Users/nkanf/projs/OpenAllay :common:test
./gradlew -PopenallayVersion=0.2.4 -PopenallayArtifactsDir=/Users/nkanf/projs/OpenAllay :fabric:build :neoforge:build verifyLoaderPackages
```

- 155 common tests passed, no failures, errors or skips.
- Both loader artifacts and package verification passed against the newly built
  core common JAR. Initial failed test runs exposed obsolete transport assertions
  and Rhino fixture loop-variable issues; corrected tests retain exact predicates.
- Independent review found and closed uncertain checkpoint admission, derived
  terrain planning/preflight races and remaining per-voxel preparation paths.
- No real-client latency measurement was run for this change. Facade dispatch
  counts are not Minecraft owner executor enqueue counts; the production bridge
  validates on the client owner and executes on the server owner.
- No product/Extension version, release tag or release publication changes.
