# Minecraft Builder Extension

Independent OpenAllay Extension for Minecraft **26.2**, Java **25**, Fabric and NeoForge.
All building algorithms, native world actions, templates and journals belong to this package.
OpenAllay core supplies only its generic Extension and JavaScript invocation interfaces.

## Build

Build the current OpenAllay common artifact first. Builder **0.2.0** requires
OpenAllay product **0.3.x** (`[0.3.0,0.4)`) and Extension API **0.2.2**
(`[0.2.2,0.3)`). Product and public API versions are independent.

```sh
/path/to/OpenAllay/gradlew -p /path/to/OpenAllay :common:jar
/path/to/OpenAllay/gradlew -p /path/to/OpenAllay-Extensions/extensions/minecraft-builder \
  -PopenallayArtifactsDir=/path/to/OpenAllay :common:test :fabric:build :neoforge:build verifyLoaderPackages
```

Alternatively use `-PopenallayCommonJar=/absolute/path/to/openallay-common-26.2-0.3.0.jar`.
The common product JAR is a **compile-only** dependency. No OpenAllay or Minecraft classes
are copied into the packages. Each loader has its own normal entrypoint and metadata.

Outputs:

- `fabric/build/libs/openallay-builder-fabric-26.2-0.2.0.jar`
- `neoforge/build/libs/openallay-builder-neoforge-26.2-0.2.0.jar`

## Online backend

The full native backend supports the active **integrated server**. It binds the exact local
player, network connection, client level, integrated server and server level. It checks
those identities, cancellation and invocation lifetime before queued native actions.
A remote server is not an authoritative local world. It returns `unsupported_topology`;
there is no ghost-client edit, command fallback, offline save edit or remote write endpoint.
Normal restricted JavaScript can open a Builder session and read this active integrated
server without JVM access. World changes require the independent, Extension-owned
`openallay_builder:world_write` grant, which is off by default. Installation, world
observation and unrestricted JavaScript do not grant it. This permission covers block
writes, undo, connection-shape repair and native physics notifications. It does not
permit remote-server writes or another Extension's methods. Builder adds no separate
creative-mode or game-master gate.

Game state is read or changed on its game owner thread. JavaScript and Extension artifact IO remain on the invocation worker. Minecraft reads an existing SavedData world identity through its normal live server API on the server owner; it creates that identity only for an authorized write or undo. Opening, world reads and status do not create it, and read-only operation listing returns no entries when no identity exists. Minecraft owns its persistence. The adapter uses immutable JSON commands, never a Rhino callback
on a game thread. Large operations use scheduling slices, not total size limits. Unloaded
chunks, invalid block states and out-of-dimension coordinates fail explicitly. This version
does not generate/load arbitrary chunks as a hidden side effect.

`scan_terrain` and `scan_ground` send one detached native scan command, rather than
queueing a client/server handshake for every inspected block. The native cursor yields
between bounded cell/column quanta and checks the normal invocation cancellation and
session binding. It scans the full requested dimension height with the same observed
ID/property rules, including explicit modded ground IDs. A primed native WORLD_SURFACE
heightmap only skips known air above a column; it never supplies the ground result.
If any possible registered native-air state has a nonvanilla air ID, or the loaded
chunk has no primed map, scanning keeps the full-height fallback. Results contain every
column or fail explicitly; cancellation never returns a half-empty scan. Scans do not
write blocks or create journal operations. Multi-slice reads are not atomic snapshots.

Generated geometry submits one detached write-region plan per contiguous geometry segment. Native
preparation and application remain cooperative 256-cell owner slices, with the same
optimistic before-image checks, durable intents and actual readbacks. This removes the
JS transport quantum without claiming fill-command speed or atomic world transactions.
Explicit bed/door placement, reads and connection repair create intentional barriers;
a preset may therefore submit multiple ordered segments. Duplicate coalescing is per
segment, not across those barriers. Each plan describes a final block image, not an ordered stream of per-assignment side
effects. For duplicate positions anywhere in one plan, the last assignment wins and the
original first before-image remains journaled. Discarded intermediate duplicate states
are not placed, so they do not trigger native replacement hooks or intermediate physics.
Explicit separate public operations still execute in order. Large plans use memory
proportional to requested assignments. The JavaScript summary `writes` counts requested
assignments; native session `writes` counts changed cells, and native receipt `verified`
counts observed readbacks. These are different measurements, not interchangeable speed
or success claims. Template capture uses batched native region reads; undo batches checks/restoration and reports edits that
race either initial capture or apply as conflicts, without overwriting them.

Writes use Minecraft block state setters with client synchronization and deferred shape
updates. `update_connections` (`updateConnections` in the native facade) normalizes native
connection shapes in the requested bounds plus a one-block halo. Actual state changes use
normal durable write/readback handling. Repair is one deterministic pass in section
order, not a fixed-point solver or a wait for later game ticks. This traversal differs
from the previous global voxel order. It no longer dispatches unconditional neighbor
notifications across every air and interior cell. `sync_physics` (`syncPhysics` natively)
is the explicit full-bounds-and-halo neighbor/comparator notification pass, with capture,
comparison and readback in bounded owner slices. It does not implicitly run shape
repair or wait for fluid, gravity, redstone or comparator behavior to settle on later
ticks. Both methods preserve JS write barriers.
Native physics and comparator propagation are not retroactively attributed to the
direct-block journal. Later changed postimages report undo conflicts instead of being
overwritten. Native block replacement can still have game effects, such as container
drops and ticking entities. Those effects are outside the block journal.

### Builder 0.2.0 behavior change

`update_connections` previously included the full region physics pass. It now performs
shape normalization only; callers that need the old full-region notification effects
must also call `sync_physics` explicitly. Presets use shape normalization by default.
This is a public Extension behavior change, not an internal format version.
Builder 0.2.0 also replaces Agent JVM access with the controlled method module
and independent world-write grant. These changes define the new public contract.
The parent-owned release process creates the tag, verifies packages and publishes
actual checksum-pinned assets; this source preparation does not publish them.

## Use

Load the bundled `minecraft-builder` Skill and its focused references. The CommonJS module
is `openallay_builder:building`. `building.open(options)` calls the controlled
`openallay_builder:native` method module. It receives an opaque invocation-local
session ID and wraps only the documented methods. Arguments and results are detached
JSON values; no Java backend, session, class or reflection object reaches the Agent.
The core freezes the player's per-Extension grant for the request. Server-origin
requests have no native write grant. Missing write authority fails explicitly; there
is no command fallback. Pure JavaScript execution keeps its normal interpreter budget.
Time spent inside a trusted native method does not consume that interpreter budget;
native work still checks cancellation and cooperatively yields on the game owners.
Call `finish()` when a construction operation succeeds. It completes the block journal;
it does not save or close Minecraft. `close()` in the JS facade finishes first. A successful JavaScript scope completes verified work during native cleanup. Failed or cancelled scopes preserve partial/interrupted journals. Explicit failures cannot be relabelled completed by cleanup. Cancellation stops queued work, not world changes already applied.

## Templates and undo

Templates are JSON application artifacts, not Minecraft region/save files. They
preserve namespaced block states and optional typed block-entity SNBT. Native block states
supply rotation/mirroring. Known coordinate-only vanilla block-entity payloads retain their
content; unsupported opaque/directional payload transforms fail instead of inventing
orientation fidelity. `write` rebases block-entity coordinates to the destination.

Artifacts live below the Minecraft instance's `config/openallay-builder/` directory.
Opening and template/journal methods can access these controlled Extension artifacts;
read-only world access is not a promise of zero artifact IO. They do not expose a
generic filesystem path or grant Agent JVM/filesystem access.
Before each native mutation quantum, the worker forces an atomic delta containing the
original and intended block images. After server readback it forces one atomic outcome
delta containing actual images and known unstarted withdrawals. Scheduling remains 256
cells; it is not a total-volume limit. The active journal does not rewrite all prior cells
for each quantum. Successful completion atomically publishes one compact checkpoint
before deleting covered deltas. Disk IO stays on the worker, never the game tick thread.
A process interruption leaves an interrupted journal; there is no startup replay or
automatic rollback. Missing/corrupt delta sequences fail explicitly without world writes.

Internal artifacts use the latest strict shape only, with no internal version field or
migration reader. Malformed or obsolete artifacts fail validation and remain untouched;
there is no replay, silent deletion or compatibility fallback. Active storage is one base
checkpoint plus strictly sequenced atomic delta files. A checkpoint sequence makes a
crash during segment cleanup safe. Explicit undo checks the expected
image before restoring each block. Intervening edits produce reported conflicts rather than
being overwritten. Undo applies only to the same live-world incarnation UUID and dimension. Minecraft owns the UUID persistence through SavedData; recreating a save at the same path does not reuse old journals. It is not a
whole-world/entity backup and is not an atomic transaction.

## Verification boundary

Automated tests cover detached algorithms, native codecs, scheduling/lifetime rejection,
artifact persistence and package contents. Loader builds are compiler/package checks.
No graphical Minecraft session or live model provider was launched for these checks.
