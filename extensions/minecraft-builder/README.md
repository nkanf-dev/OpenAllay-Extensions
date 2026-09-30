# Minecraft Builder Extension

Independent OpenAllay Extension for Minecraft **26.2**, Java **25**, Fabric and NeoForge.
All building algorithms, native world actions, templates and journals belong to this package.
OpenAllay core supplies only its generic Extension and JavaScript invocation interfaces.

## Build

Build the current OpenAllay common artifact first. This package requires OpenAllay product
**0.2.3** and Extension API **0.2.1** or newer within the 0.2 series.

```sh
/path/to/OpenAllay/gradlew -p /path/to/OpenAllay :common:jar
./gradlew -PopenallayArtifactsDir=/path/to/OpenAllay :common:test :fabric:build :neoforge:build verifyLoaderPackages
```

Alternatively use `-PopenallayCommonJar=/absolute/path/to/openallay-common-26.2-0.2.3.jar`.
The common product JAR is a **compile-only** dependency. No OpenAllay or Minecraft classes
are copied into the packages. Each loader has its own normal entrypoint and metadata.

Outputs:

- `fabric/build/libs/openallay-builder-fabric-26.2-0.1.0.jar`
- `neoforge/build/libs/openallay-builder-neoforge-26.2-0.1.0.jar`

## Online backend

The full native backend supports the active **integrated server**. It binds the exact local
player, network connection, client level, integrated server and server level. It checks
those identities, cancellation and invocation lifetime before queued native actions.
A remote server is not an authoritative local world. It returns `unsupported_topology`;
there is no ghost-client edit, command fallback, offline save edit or remote write endpoint.
Builder requires the already authorized unrestricted JavaScript invocation. Installation
does not turn that capability on. It adds no creative-mode or game-master gate.

Game state is read or changed on its game owner thread. JavaScript and Extension artifact IO remain on the invocation worker. Minecraft may load its own SavedData identity through the normal live server API on its owner thread; Minecraft owns its persistence. The adapter uses immutable JSON commands, never a Rhino callback
on a game thread. Large operations use scheduling slices, not total size limits. Unloaded
chunks, invalid block states and out-of-dimension coordinates fail explicitly. This version
does not generate/load arbitrary chunks as a hidden side effect.

Writes use Minecraft block state setters with client synchronization and deferred shape
updates. `updateConnections` invokes native neighbor shape logic for the requested bounds
plus a one-block halo, then dispatches normal neighbor and comparator-output notifications in bounded slices. The halo is read back after the pass. Native physics and comparator propagation are not retroactively attributed to the direct-block journal: edits by other actors between slices must not become our expected undo state. Later changed postimages report undo conflicts instead of being overwritten. Native block replacement can still have game effects, such as
container drops and ticking entities. Those effects are outside the block journal.

## Use

Load the bundled `minecraft-builder` Skill and its focused references. The CommonJS module
is `openallay_builder:building`. The module opens a worker-bound native session through
`Java.type("dev.openallay.builder.BuilderRuntime").open(...)`.
Call `finish()` when a construction operation succeeds. It completes the block journal;
it does not save or close Minecraft. `close()` in the JS facade finishes first. A successful JavaScript scope completes verified work during native cleanup. Failed or cancelled scopes preserve partial/interrupted journals. Explicit failures cannot be relabelled completed by cleanup. Cancellation stops queued work, not world changes already applied.

## Templates and undo

Templates are versioned JSON application artifacts, not Minecraft region/save files. They
preserve namespaced block states and optional typed block-entity SNBT. Native block states
supply rotation/mirroring. Known coordinate-only vanilla block-entity payloads retain their
content; unsupported opaque/directional payload transforms fail instead of inventing
orientation fidelity. `write` rebases block-entity coordinates to the destination.

Artifacts live below the Minecraft instance's `config/openallay-builder/` directory.
Before mutation, the worker atomically persists the original and intended block image.
After server readback it persists verification. A process interruption leaves an interrupted
journal; there is no startup replay or automatic rollback. Explicit undo checks the expected
image before restoring each block. Intervening edits produce reported conflicts rather than
being overwritten. Undo applies only to the same live-world incarnation UUID and dimension. Minecraft owns the UUID persistence through SavedData; recreating a save at the same path does not reuse old journals. It is not a
whole-world/entity backup and is not an atomic transaction.

## Verification boundary

Automated tests cover detached algorithms, native codecs, scheduling/lifetime rejection,
artifact persistence and package contents. Loader builds are compiler/package checks.
No graphical Minecraft session or live model provider was launched for these checks.
