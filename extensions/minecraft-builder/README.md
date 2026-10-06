# Minecraft Builder Extension

Builder **0.4.0** is one native-neutral Extension JAR using **Extension API 0.4.0**.
Its production classes target **Java 8**. One universal payload uses the exact
Minecraft/loader targets in its support manifest and the host's native world adapters.
The task-branch Forge 1.16.5 candidate has passed normal discovery, restricted Tool
writes, partial failure, cancellation and undo on stock Forge36.2.42 with Java17.
`forge-1.16.5-native-acceptance.json` binds the tested bytes and the exact validation scope.
This is not a published release or a claim that every preset is available.

## One package, stable host

Geometry, terrain, presets, templates, journals, Skills and JavaScript stay in this
repository. Minecraft-specific block/NBT/SavedData operations and owner scheduling
are supplied by core `MinecraftWorldAccess` / `WorldSession` adapters. The Extension
has no Minecraft or loader class dependency and no per-game loader entrypoint.
Gson 2.14 is relocated privately; SDK and game classes are not bundled.

Build the compatible public SDK, then use the checked-in wrapper:

```text
/path/to/OpenAllay/gradlew -p /path/to/OpenAllay :extension-api:jar
./gradlew -PopenallayExtensionApiJar=/path/to/openallay-extension-api-0.4.0.jar build
```

Output: `universal/build/libs/openallay-builder-universal-0.4.0.jar`.
`verifyUniversalPackage` checks Java 8 classfiles, the exact external support manifest,
private JSON dependency isolation, canonical resources and absence of native/loader/SDK
classes. The thin JAR is not the distributable package.

Install the universal JAR in `config/openallay/extensions/` and restart a compatible
host. Do not put it in `mods/` or install a legacy loader Builder beside it. The current
published OpenAllay 0.4.1 packages still bundle Builder 0.2.1; this new source integration
and its pinned development core are separate from that immutable release.

## Authority and lifecycle

The host advertises `minecraft:world-access` only when its native adapter exists.
Enabling Minecraft Builder enables its building operations. Opening Builder is lazy
and binds the exact active player, connection and integrated world through the
invocation-owned SDK. Read access does not create a world identity. The first write
or undo initializes that identity. These operations work in restricted JavaScript
without Agent JVM access.

Native work runs on the game owner. Scripts and artifact IO remain on their worker.
Queued work rechecks invocation lifetime, exact identity, world lifetime and loaded
chunks. Closing stops queued operations; it is not an implicit world rollback. A changed write with unavailable readback keeps its
durable pending intent and original failure, not fabricated air or success.

## Modules and artifacts

Load the `minecraft-builder` Skill and its focused references. Use
`require('openallay_builder:building').open(options)` in restricted JavaScript;
no Agent JVM access is required. The actual native method binding is
`openallay_builder:native`. Large region work uses cooperative slices, not a total
volume cap. Unloaded chunks are not generated as a hidden side effect.

Preset defaults use the native context's 47 material roles. IDs and every requested
property are validated on the host. Missing roles fail before writes; modern block
names or `waterlogged` properties are not silently assumed in older games. Explicit
caller materials remain exact native states.

Templates and journals remain under `config/openallay-builder/`. Their current JSON
shapes are unchanged: no internal format version, migration or replay branch. `finish`
completes a journal group; undo creates its own group and restores only verified
matching after-images. Later edits report conflicts. Opaque block-entity SNBT preserves
actual native data; unsupported transforms fail rather than inventing orientation.
The journal is not a whole-world/entity backup or an atomic transaction.

## Universal architecture evidence

The prior Builder 0.3.0 payload established the universal-package baseline:

- 211 shared detached domain/storage/JavaScript fixture tests passed.
- One privately shaded JAR ran unchanged on Java 8 and Java 25 with official Gson 2.8.0
  in the parent; all 318 production/dependency classes were major 52.
- Both actual 26.2 development clients loaded that JAR through normal discovery.
  Restricted Tool calls exercised reads, batch build/template/finish and journal undo.
  Independent native owner readback verified all seven cells and the diamond chest
  SNBT were restored. Framework shutdown released scopes and classloaders.
- Native codec/transform/SavedData/connection tests live in the core adapter module;
  canonical preset emitted states are cross-checked with its real registry.

Builder 0.4.0 removes the Extension-private write gate and uses public SDK 0.4.0.
Its contract tests cover enabled restricted-JavaScript reads/writes and real
closed, cancelled and foreign-invocation rejection, with journal outcomes unchanged.

The manifest declares exact package compatibility; runtime evidence is recorded separately.
On Forge1.16.5, the native palette intentionally omits `lightning_rod_up` because
Minecraft has no lightning rod. `build_skyscraper` therefore fails before writes;
the shared recipe does not substitute another block. Stock Forge1.12.2 remains
unvalidated. Java8 bytecode alone does not establish Minecraft support.
