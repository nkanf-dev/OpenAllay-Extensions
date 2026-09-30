# Building presets

These six methods are installed by `openallay_builder:building`. They use the current online session. They do not read or edit world-save files.

```javascript
var builder = require("openallay_builder:building").open();
builder.build_simple_house(100, 64, 100);
builder.build_cottage(120, 64, 100, {facing: "east", name: "Riverside"});
builder.build_farm(140, 64, 100, {width: 2, depth: 6, crops: ["beetroots"]});
builder.build_dock(150, 64, 100, {length: 18, width: 4});
builder.finish();
```

## Shared rules

All six methods have the signature `(x, y, z, options)`.

- Coordinates and dimensions are finite 32-bit integers. `y` is the top of the foundation, farm soil, or dock deck. It is **not** the first wall-block height.
- Omit `options` to use the defaults. Unknown option names fail instead of being ignored. Conflicting short and descriptive dimension aliases also fail.
- Every method accepts `facing: "north" | "east" | "south" | "west"`. The default is north, except for cottages, which default to south.
- The north-facing local plan grows along `+x` and `+z`. Its front is the `-z` side. The anchor remains fixed when rotated: east maps local `(u,w)` to `(-w,u)`, south to `(-u,-w)`, and west to `(w,-u)`. Therefore, a south-facing house extends into negative world x/z from its anchor. A windmill uses the anchor as its tower center. A dock extends away from its entrance along local `+z`.
- Rotation applies to both positions and block states. The native state transformer handles facing, axes, directional properties, and custom block behavior. There is no preset-level `mirror` option.
- Material options accept a block ID or `{id, properties}` BlockSpec unless the table says otherwise. Bare IDs use the `minecraft` namespace and the library's aliases. Native registry/state validation runs before placement. Geometry-required properties, such as stair facing or log axis, override those same properties in the supplied BlockSpec.
- `doorMaterial` accepts a species/material name such as `oak`, `birch`, or `iron`; an unqualified door ID such as `oak_door`; or a qualified door block ID such as `example:cedar_door`. The block must support the native door properties.
- Structural minimum dimensions keep named features usable. There are no arbitrary maximum dimensions. World build-height and integer-overflow bounds are checked before the first write. The native session can still reject unloaded cells, cancelled work, or invalid live placements. Presets are ordered online writes, not a promise of all-or-nothing rollback of the entire building.
- Each method clears the required headroom. Cottage, windmill, and farm first flatten a bounded area with three underground layers. They require observed/loaded cells for that terrain step; unknown cells are not treated as air. Existing builds or terrain inside these footprints can be replaced.
- Furniture and roof supports are completed before linked beds/doors are placed. The final connection update runs after the linked blocks exist. Custom materials must provide the support their selected roles require.

Each result contains:

```javascript
{
  operation: "build_cottage", // the called method
  writes: 1234,               // submitted assignments, including replacement and clearing
  bounds: {
    min: {x: 10, y: 61, z: 10},
    max: {x: 20, y: 76, z: 18}
  }
  // plus preset-specific dimensions and facing
}
```

`bounds` are inclusive affected/cleared bounds, not just the final non-air structure. Writes are not a unique-block count; native status reports actual changed cells and partial failures. Cottage results also contain `name`; this is metadata, **not** sign text. Farms also return `planted` and `cropTypes`. Windmills return the effective `bladeLength`.

## `build_simple_house(x, y, z, options)`

Creates a deep foundation, wooden shell, corner logs, cleared interior, slab roof with a one-block overhang, linked door, windows, table, furnace, chest, linked bed, hanging lantern, and front entrance stair. Each furniture position is distinct from the door and both bed cells.

| Option | Default | Rule |
| --- | --- | --- |
| `w` / `width` | `7` | At least 7 |
| `d` / `depth` | `7` | At least 7 |
| `h` / `height` | `5` | At least 4; wall height above `y` |
| `facing` | `"north"` | Shared facing rule |
| `foundation` | `"cobblestone"` | Solid support material; fills `y-2..y` before the floor |
| `wall` | `"oak_planks"` | Wall block |
| `log` | `"oak_log"` | Corner block with `axis: "y"` |
| `floor` | `"oak_planks"` | Solid floor at `y` |
| `roof` | `"stone_brick_slab"` | Slab; uses `type: "bottom"`, `waterlogged: "false"` |
| `window` | `"glass_pane"` | Window block |
| `doorMaterial` | `"oak"` | Shared door-material rule |
| `bedColor` | `"red"` | One of Minecraft's 16 color names |

The table is an oak fence with an oak pressure plate. The furnace and chest face the front. Bed head points toward the front. The lantern hangs directly beneath the roof. The bed is at local `(1,1,d-2)` and `(1,1,d-3)`; no later furniture write can replace either half.

## `build_skyscraper(x, y, z, options)`

Creates a deep foundation, checkerboard floor plates, floor-by-floor glass color cycle, iron corner piers, ceilings, lights on every floor, front door/canopy/steps, roof wall, antenna, and lightning rod. A backed ladder connects the floors and roof.

| Option | Default | Rule |
| --- | --- | --- |
| `w` / `width` | `15` | At least 5 |
| `d` / `depth` | `15` | At least 5 |
| `floors` | `12` | Positive integer |
| `floor_h` / `floorHeight` | `5` | At least 3; floor-plate spacing |
| `facing` | `"north"` | Shared facing rule |
| `foundation` | `"stone_bricks"` | Foundation material |
| `foundationDepth` | `4` | Positive integer; fills from `y-foundationDepth` through `y` |
| `floor` | `"smooth_stone"` | First checkerboard color |
| `alternateFloor` | `"polished_andesite"` | Second checkerboard color |
| `glass` | `["light_blue_stained_glass", "cyan_stained_glass", "blue_stained_glass"]` | Nonempty array of blocks; repeated by floor |
| `pier` | `"iron_block"` | Corners and ladder backing; must provide solid backing |
| `ceiling` | `"smooth_stone"` | Ceiling and roof plate |
| `roofWall` | `"stone_brick_wall"` | Roof perimeter |
| `antenna` | `"iron_bars"` | Four-block antenna above roof |
| `doorMaterial` | `"oak"` | Shared door-material rule |

The roof plate is at `y + floors * floorHeight`. The rod is five blocks above that plate. Clearing reserves one additional air block above the rod. Floor lights are installed after all checkerboard plates, so upper floors cannot overwrite lower-floor ceiling lights.

## `build_cottage(x, y, z, options)`

Flattens a foundation and creates a patterned floor, corner/log-beam frame, walls and windows, stair/slab pitched roof with overhang, linked door, supported hanging lantern, chimney, and lit campfire. All four facings rotate the entire structure, not only the door.

| Option | Default | Rule |
| --- | --- | --- |
| `w` / `width` | `8` | At least 5 |
| `d` / `depth` | `7` | At least 5 |
| `h` / `height` | `4` | At least 3; wall height |
| `facing` | `"south"` | Shared facing rule |
| `name` | `"Cottage"` | Nonempty string; returned metadata only |
| `wall` | `"oak_planks"` | Wall and roof-gable infill |
| `roof_stair` / `roofStair` | `"dark_oak_stairs"` | Stair block; facing/half/shape/waterlogging set by roof layout |
| `roof_slab` / `roofSlab` | `"dark_oak_slab"` | Bottom ridge slab |
| `log` | `"oak_log"` | Vertical corners and horizontal beams; native rotation changes axes |
| `foundation` | `"cobblestone"` | Flattened surface and three layers below it |
| `floor` | `"spruce_planks"` | First checkerboard color |
| `alternateFloor` | `"oak_planks"` | Second checkerboard color |
| `window` | `"glass_pane"` | Window block |
| `doorMaterial` | `"oak"` | Shared door-material rule |
| `chimney` | `"bricks"` | Chimney column; supports the lit campfire |

The flattened footprint extends one block beyond each side. Odd and even widths keep an inclusive roof span and one- or two-cell slab ridge. The chimney rises above the ridge. `name` does not place a sign or write block-entity text.

## `build_windmill(x, y, z, options)`

Flattens a work area and creates a tapered hollow tower with lower stone and upper concrete walls, front door, plank roof, log axle, and four fence-and-wool sail arms. The anchor is the tower center.

| Option | Default | Rule |
| --- | --- | --- |
| `height` | `15` | At least 5; tower wall height |
| `radius` | `3` | At least 2; base radius; top radius is `radius-1` |
| `bladeLength` | `min(radius+2, height-4)` | Positive; at most `height-4` so the lower sail stays above the entrance |
| `facing` | `"north"` | Side with the door and blades |
| `foundation` | `"cobblestone"` | Flattened surface and three layers below it |
| `lowerWall` | `"stone_bricks"` | Lower tower |
| `upperWall` | `"white_concrete"` | Upper tower |
| `bladeFence` | `"oak_fence"` | Radial sail spars |
| `blade` | `"white_wool"` | Outer sail panels |
| `roof` | `"spruce_planks"` | Filled circular roof cap |
| `doorMaterial` | `"oak"` | Shared door-material rule |

Short towers automatically use shorter default blades. An explicitly impossible blade length fails before placement. Each of the four cardinal arms always has fence and wool, including the shortest valid tower.

## `build_farm(x, y, z, options)`

Flattens the area and creates irrigated farmland, mature crops, an outside canal, fence perimeter, and supported front gate. `w` and `d` are the exact cultivated-layout footprint, not the larger outer fence footprint.

| Option | Default | Rule |
| --- | --- | --- |
| `w` / `width` | `20` | Positive integer, including 1 |
| `d` / `depth` | `16` | Positive integer, including 1 |
| `crops` | `["wheat", "carrots", "potatoes", "beetroots"]` | Nonempty array; crop rules below |
| `facing` | `"north"` | Gate side |
| `fence` | `"oak_fence"` | Perimeter above the outside canal |
| `gate` | `"oak_fence_gate"` | Front gate; facing/open/in_wall/powered set by layout |
| `foundation` | `"dirt"` | Underground, retaining rim, and solid gate support |

Crop entries can be block-ID strings or `{block: <BlockSpec or ID>, age: <integer>}`. Strings default to mature ages for known farmland crops:

- `wheat`, `carrots`, and `potatoes`: age 7.
- `beetroots`: age 3, never age 4..7.

Explicit ages must be nonnegative and within the known native crop's range. Other registry crops require an explicit age; native registry/property validation must accept it. Custom crops must be appropriate for farmland. The preset does not infer modded substrate, light, or growth rules. An empty crop array fails before terrain changes.

The fence/canal perimeter is one block outside the footprint. A solid containing rim is two blocks outside it. Interior water channels occur at local x 8, 17, 26, and so on. Every farmland cell is within four blocks of water. Farmland uses `moisture: "7"`. Remaining cells cycle through the crop array in row-major order. A `1x1` farm still has one planted crop, water, fence, and gate. Internal canals consume footprint cells; `planted` reports the resulting exact crop count.

## `build_dock(x, y, z, options)`

Creates a rectangular plank deck, log pilings, side fence rails, and two end lanterns. `width` is exact; even widths are never rounded up.

| Option | Default | Rule |
| --- | --- | --- |
| `length` | `18` | Positive integer, including 1 |
| `width` | `5` | Positive integer, including 1 |
| `facing` | `"north"` | Entrance side; dock extends away from it |
| `deck` | `"spruce_planks"` | Exactly `length * width` final deck blocks at `y` |
| `log` | `"spruce_log"` | Vertical pilings |
| `rail` | `"spruce_fence"` | Side rails and end lantern posts |
| `pilingDepth` | `4` | Positive integer; below-deck pilings from `y-pilingDepth` through `y-1` |
| `pilingSpacing` | `4` | Positive integer; first, last, and each multiple row receive pilings |

For widths 3 and larger, rails sit above the two deck edges. For widths 1 and 2, rails and their supporting pilings sit just outside the deck, so they do not block the walking cells. These outside posts are not extra deck blocks. End posts reach `y+2`; lanterns stand at `y+3`. The preset clears three blocks above its occupied footprint. It does not flatten the shore or infer water depth; choose `pilingDepth` to reach the desired support level.

## Verification notes

`BuilderPresetsContractTest` runs the shipped CommonJS sources in the real OpenAllay Rhino runtime. It checks semantic landmarks for all six defaults, whole-cottage rotations and block states, linked furniture cells, every-floor skyscraper lights, narrow farm cases, native crop-age prevalidation, custom palettes, short windmills, exact dock widths, and failure-before-write bounds/options checks.

The fixture's state transformer is a deliberately limited test double. It proves that every preset delegates state rotation with the correct angle, not that Minecraft's registry accepts every state. Native registry tests and live final-readback checks are still required for loader/game acceptance, supports, neighbor updates, water behavior, and visual quality. No offline or third-party source execution is used as an oracle.
