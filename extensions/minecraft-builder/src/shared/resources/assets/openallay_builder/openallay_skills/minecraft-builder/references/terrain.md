# Terrain and paths

The `openallay_builder:terrain` ES5 CommonJS module exports `install(api, util)`.
The public `openallay_builder:building` module installs it on each builder session.
Use `builder = require("openallay_builder:building").open(options)` through the
Extension's normal authorized online session. These functions never edit save files.

## Shared rules

- X/Y/Z inputs are finite integer block coordinates. X/Z rectangles include both
  ends and accept reversed bounds. Y ranges passed to `clear_vegetation` include
  both ends. Scan options use an **exclusive** `maxY` instead.
- A dimension provides `context().minY` and exclusive `context().maxY`. Scans use
  that full height unless explicit `minY`/`maxY` options restrict the scan.
  There is no Y30..100 assumption, terrain-relative scan band, search expansion
  cap, or hidden fallback route. Reads stop once the requested surface is found.
- A read that returns null, unknown, unloaded, or an invalid block is an error.
  Unknown blocks are never treated as air. Reads that fail while preparing an
  operation cause no writes. This is preflight, **not** a transaction: a backend
  write failure or world changes during application can still leave partial work.
- Scan/path classification is based on observed block IDs and properties, not
  historical provenance or inferred collision shapes. Natural stone in a wall is
  indistinguishable from terrain stone. Ordinary logs in a house are
  indistinguishable from tree logs. Use explicit bounds and inspect the site.
- Natural ground is the union of `builder.NATURAL_GROUND` and common terrain
  blocks (including dirt paths, farmland, snow, rooted dirt, mud, ice, nylium,
  bedrock and end stone). Ground classification excludes air, vegetation,
  water/lava/bubble columns, and waterlogged blocks. Unknown modded blocks are
  not assumed to be ground. `groundBlocks: ["mod:soil", ...]` adds explicit IDs;
  it does not replace the default set or override the air/liquid/vegetation rules.
- Vegetation includes vanilla plants, crops, fungi, vines, ordinary logs, saplings
  and nonpersistent leaves. Stripped logs, crafted wood blocks and persistent
  leaves are not vegetation. Modded vegetation is not guessed from its name.
- A material may be a block ID or a full `BlockSpec`
  `{id, properties, blockEntity?}`. Normal builder validation applies. State and
  typed block-entity payloads are passed intact to `place_block`.
- When `seed` is absent, seeded operations use the session's seed. The same input
  world, arguments, seed and session settings produce the same route/palette/blend.
  Each operation starts its own PRNG. The route does not depend on palette draws.
- Mutating calls return the normal `{operation, writes}` summary plus fields
  specified below. `writes` counts submitted assignments, including vegetation air
  writes; native status distinguishes actual changed cells and partial failures. `columns` counts distinct surface columns, not all writes.

## Scan terrain

```javascript
builder.scan_terrain(x1, z1, x2, z2, options?)
builder.scan_ground(x1, z1, x2, z2, options?)
```

Options:

| Name | Default | Meaning |
| --- | --- | --- |
| `minY` | dimension minimum | Inclusive bottom of scan; must be in dimension |
| `maxY` | dimension maximum | Exclusive top of scan; must be in dimension |
| `groundBlocks` | `[]` | Extra ground IDs, used only by `scan_ground` |

The result is an array ordered by increasing X, then increasing Z. Each column is
`{x, z, y, block, properties}`. `block` is a qualified ID, not a `BlockSpec`.
A column with no match is `{x, z, y:null, block:null, properties:null}` and is not
omitted. `scan_terrain` selects the highest nonair block, including buildings,
leaves and water. `scan_ground` selects the highest accepted ground block beneath
those objects. It reports ground below water/buildings; a later path clearance
check determines whether that ground is usable. Caves do not change the highest
accepted ground choice. Scans do not copy block entities.

The native backend evaluates the same detached ID/property predicate in cooperative
owner-thread batches. It does not call JavaScript on a game thread or queue one owner
round trip per block. A primed WORLD_SURFACE map is only a conservative upper bound
for skipping known vanilla air. It never replaces the ground predicate. Custom
native-air IDs or an unprimed map keep the full-height fallback, so modded blocks do
not silently disappear. The `create(backend)` contract requires detached region reads, native column scans and
batch writes; it does not silently fall back to per-voxel owner round trips.

Scans are read-only and do not create block-journal operations. Flattening, vegetation clearing and path planning also capture their needed columns
through native region batches, rather than one owner round trip per voxel. Conditional
edits carry their observed full before-image (including block-entity payloads) into native
preflight; intervening changes fail instead of being adopted as a new overwrite baseline.
Flatten/clear planning discards each read-only column after deriving its edits, so sparse
sites do not retain an entire world-ceiling volume of air rows. Reads across batches
are non-atomic observations of the live world. Cancellation, unloaded chunks or
missing native blocks fail the call rather than returning partial rows or guessing
a missing column. Scheduling quanta do not limit the rectangle or scan height.

```javascript
const terrain = builder.scan_ground(-8, -8, 8, 8, {
    groundBlocks: ["example:limestone"]
});
```

## Summarize scanned bounds

```javascript
builder.get_terrain_bounds(columns)
```

Pass the array from either scan. Returns
`{x1,z1,x2,z2,minY,maxY,count,found,missing}`. Horizontal extrema include missing
columns. Vertical extrema include found columns only. Here `maxY` is the highest
found block Y, **inclusive**, not the exclusive world/scan limit. Empty arrays
produce null extrema and zero counts. An all-missing scan has horizontal bounds
but null vertical extrema. This function does not read the world.

## Flatten a build site

```javascript
builder.flatten_area(x1, z1, x2, z2, targetY, options?)
```

`targetY` is the surface block's Y, not the player's standing Y. Core columns are
set to that height even if there is no existing natural ground.

| Name | Default | Meaning |
| --- | --- | --- |
| `surface` | `"grass_block"` | New surface material |
| `underground` | `"dirt"` | Material below surface |
| `depth` | `3` | Nonnegative number of blocks below surface; clipped at dimension minimum |
| `clearAbove` | `true` | `true`: remove every nonair block above new surface to world ceiling; `false`/`0`: no clearing; integer N: clear the next N layers, clipped at ceiling |
| `blendRadius` | `0` | Nonnegative integer count of columns in an outer square annulus |
| `seed` | session seed | Seed for fractional blend rounding |
| `minY`, `maxY` | full dimension | Scan bounds for natural ground in the blend annulus only |
| `groundBlocks` | `[]` | Extra accepted ground in that annulus |

**Flattening is deliberately destructive.** Default clearing removes buildings,
liquids and vegetation above the new surface. Foundation and surface writes also
replace existing blocks. `clearAbove:false` preserves blocks above the surface;
it does not prevent foundation/surface replacement. No automatic support fill to
bedrock or bridge pillars is added. Depth 0 writes the surface only.

For an annulus column at Chebyshev distance `d` (1 through `blendRadius`) from the
core rectangle, the interpolated height is
`oldY + (targetY-oldY) * (1-d/(blendRadius+1))`.
Seeded stochastic rounding chooses its floor or ceiling according to the
fractional part. Blend columns get the same surface/foundation/clearing policy.
Columns with no accepted natural ground in the explicit scan window are skipped.
The core plus annulus is read/planned before any write. With `clearAbove:true`,
clearing always reaches the native world ceiling even when the blend scan window
is restricted. With numeric `clearAbove`, only that many layers are read/cleared.
Nothing outside the annulus is edited. Return adds `{columns,targetY,blendRadius}`.

## Clear vegetation or an entire volume

```javascript
builder.clear_vegetation(x1, y1, z1, x2, y2, z2, options?)
```

`options.mode` defaults to `"vegetation"`. Only recognized vegetation is removed;
normal building blocks and standalone water/lava are retained. This classification
is not provenance-aware: a placed ordinary log is still classified as vegetation.
Waterlogged vegetation is also vegetation and can be removed by this explicit
clearing call. Use `{mode:"all"}` to remove every nonair cell, including structures
and liquids. Both forms preflight every cell in the inclusive cuboid. Invalid mode,
invalid coordinates or out-of-dimension bounds throw before writes.

## Straight paths

```javascript
builder.build_path({x:startX,z:startZ}, {x:endX,z:endZ}, options?)
builder.build_path(x1, z1, x2, z2, y, options?) // fixed-Y compatibility overload
```

The centerline uses integer Bresenham steps. Both endpoints are included in all
quadrants. Equal endpoints produce one centerline point and one complete
footprint. `diagonal` does not change the Bresenham centerline; use smart routing
for cardinal-only movement. No alternative route is attempted if the line is
blocked. A diagonal step still requires both orthogonal intermediate footprints
and their slopes to be traversable, so it never cuts through a blocked corner.

Shared path options:

| Name | Default | Meaning |
| --- | --- | --- |
| `width` | `1` | Positive integer side length of every square footprint; never rounded to odd |
| `blocks` | `["dirt_path"]` | Nonempty palette of IDs/BlockSpecs, or one material; equal probability |
| `seed` | session seed | Deterministic palette seed |
| `maxStep` | `1` | Nonnegative maximum height spread inside each footprint and maximum height difference of corresponding cells between consecutive footprints |
| `heightPenalty` | `1` | Finite nonnegative extra cost per absolute center-height change; affects smart route choice |
| `clearance` | `2` | Nonnegative number of layers immediately above each paved block that must be air or recognized vegetation |
| `bounds` | no limit for straight | Optional inclusive `{x1,z1,x2,z2}` that must contain the entire footprint, not just its center |
| `minY`, `maxY` | full dimension | Optional scan window for ground-following paths |
| `groundBlocks` | `[]` | Extra accepted natural ground |
| `y` | absent | Straight paths only: fixed surface Y; bypasses natural-ground scanning |

A footprint's offsets on each horizontal axis run from
`-floor((width-1)/2)` through that lower offset plus `width-1`. Thus width 2 uses
`[0,1]` and width 4 uses `[-1,0,1,2]`: even widths bias toward positive X/Z.
The final paved set is the union of these square footprints, including square
endpoint caps. This definition guarantees exactly W columns on an axis-aligned
cross-section; it is not a circular or Euclidean-distance brush for diagonals.
Each distinct column is written once regardless of overlap.

By default each footprint cell follows its own highest accepted natural ground;
it does not force all side cells onto the center's Y. Missing ground is blocked.
At fixed `y`, air and existing nonliquid surfaces (including brick floors) may be
replaced, so the function can build an unsupported bridge or repave a floor.
The caller is responsible for bridge support and material survival under native
block rules. Fixed-Y paths do **not** clear existing nonvegetation headroom.

A liquid or waterlogged surface/headroom is blocked. Headroom must fit below the
native world ceiling. Vegetation in headroom is removed; any other nonair block
blocks the route. Blocks above the requested clearance are neither checked for
movement nor removed. Numeric fixed-Y overload `y` overrides `options.y`.

## Smart terrain paths

```javascript
builder.build_smart_path({x:startX,z:startZ}, {x:endX,z:endZ}, options?)
```

All shared path options apply except `y`, which is rejected. Additional option:
`diagonal` (boolean, default `true`) permits 8-neighbor movement; `false` uses four
cardinal neighbors. Explicit `bounds` are strongly recommended for detours.
Without them, search is limited to the inclusive rectangle enclosing both
endpoint footprints. It does **not** silently enlarge a straight endpoint rectangle
or assume a safe radius. This finite public search domain avoids a hidden work cap.

A* uses a binary min heap. Edge cost is 1 for cardinal movement or `sqrt(2)` for a
diagonal, plus `heightPenalty * abs(nextCenterY-currentCenterY)`. The heuristic is
Manhattan for cardinal-only movement and octile for diagonal movement. It ignores
nonnegative height penalties and is admissible. Equal priorities are resolved by
heuristic value, then insertion order. There is no random path fallback.

Every candidate checks the full footprint, its internal height spread and all
corresponding-cell height transitions. For a diagonal step both orthogonal
intermediate footprints must be valid, with permitted transitions on both sides.
A width-2 path therefore cannot squeeze through a width-1 opening. The algorithm
returns a minimum-cost route in this exact graph, not an optimal route under an
unmodeled avatar collision system. It does not dig tunnels, remove buildings,
fill valleys, cross liquids or add stairs automatically.

## Path results and failures

Both path functions return
`{operation,writes,status,path,cost,reason,width,visited,columns}`.

- Success: `status:"built"`, `path:[{x,y,z},...]`, finite `cost`, and `reason:null`.
  The path includes start/end. `path` describes centers; `columns` is the union of
  paved footprint columns. `visited` counts A* nonstale heap pops for smart paths
  or accepted centerline points for straight paths.
- Known blocked geometry: `status:"no_route"`, `path:[]`, `cost:null`, `columns:0`
  and no writes. Smart reasons are `"blocked_start"`, `"blocked_end"` or
  `"no_route"`. Straight reason is `"blocked_path"`. Out-of-bounds endpoint
  footprints are known blocked geometry.
- Invalid arguments and unknown/unloaded reads throw. They are not silently
  converted into a no-route result. No cells are written until the route and its
  paving/clearance edits are prepared. A backend write failure is still a normal
  operation failure and is not hidden as a no-route result.

```javascript
const route = builder.build_smart_path({x:-12,z:-8}, {x:22,z:14}, {
    bounds: {x1:-20,z1:-16,x2:30,z2:22},
    width: 2, maxStep: 1, heightPenalty: 2, diagonal: true,
    blocks: ["gravel", "coarse_dirt"], seed: "garden-walk"
});
if (route.status !== "built") {
    // Inspect route.reason; do not assume that paving happened.
}
```
