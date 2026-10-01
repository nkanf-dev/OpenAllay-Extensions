# Setup and blocks

```js
const building = require("openallay_builder:building");
const b = building.open({dimension: "minecraft:overworld", seed: 42});
```

`open_world(options)` and `quick_setup(options)` alias `open`.
`ensure_deps()` reports the module and Extension IDs. `resolve_save_path()`
explains that no offline save path is accepted. No Python or Amulet dependency
is needed. `create(backend, options)` injects a detached backend for tests.

`b.context()` returns the dimension, version, dataVersion, player, minY and
maxY. minY is inclusive; maxY is exclusive. `get_player_pos()` and
`detect_version()` are convenience views. Positions are finite signed 32-bit
integers. Bounds in shape APIs include both endpoints and normalize reversal.

- `place_block(x,y,z, block, properties?)` writes a BlockSpec or ID.
- `get_block(x,y,z)` returns a full namespaced ID.
- `get_block_full(x,y,z)` returns `{id,properties,blockEntity?}`.
- `read_region(x1,y1,z1,x2,y2,z2)` returns every cell as `{x,y,z,state}`, ordered
  by y, then z, then x. Reversed inclusive bounds are normalized.
- `get_blocks([{x,y,z},...])` returns the same cell structure for arbitrary
  positions, preserving input order and duplicates without reading their bounding box.
  All three full-state read APIs capture fresh native observations, including block entities.
- `transform_state(block, degrees, mirror)` uses the native block implementation.
- `update_connections(x1,y1,z1,x2,y2,z2)` normalizes native connection shapes
  in the bounds and boundary halo, including fences, panes, walls and stairs.
  It writes and verifies actual state changes, without a full-volume physics pass.
- `sync_physics(x1,y1,z1,x2,y2,z2)` explicitly runs native neighbor and comparator
  notifications across the bounds and halo, with full readback. Use it when a
  whole-region physics pass is needed.

For example, filter a detached region snapshot without one bridge call per cell:

```js
return b.read_region(-2, 64, -2, 2, 66, 2).filter(function (cell) {
  return cell.state.id === "minecraft:stone_bricks";
});
```

BlockSpec properties are string values. A block entity is typed SNBT text, not
an untyped JSON approximation. Native registry validation rejects unknown IDs
or invalid property values. Missing block entity data follows native creation
semantics; copying a supplied SNBT payload restores readable block entity data.
Unobserved or unloaded cells are errors, never implicit air.

Unqualified IDs gain `minecraft:`. Qualified IDs, including modded namespaces,
remain unchanged. `_fix_block_name(id)` and `BLOCK_ALIASES` expose the eleven
convenience aliases: tulip_red/orange/pink/white, oak_plank, spruce_plank,
birch_plank, dark_oak_plank, stone_brick, cobble and wood.
`FLOWERS`, `POTTED_FLOWERS`, `NATURAL_GROUND`, and `BUILDING_BLOCKS` contain
qualified palette IDs. These are convenience data, not native registry rules.

## Batch custom loops

`b.batch(callback)` groups adjacent writes into one native plan. The callback
receives `b`. Reads, linked blocks, connection updates, and lifecycle calls are
flush barriers. Nested batches share the pending plan. It returns the callback's
value, or `{operation:"batch",writes}` when the callback returns nothing.

```js
b.batch(function (b) {
  for (var x = 0; x < 12; x++) b.place_block(x, 64, 0, "stone_bricks");
});
```

This is transport batching, not an all-or-nothing transaction. Errors discard
unflushed writes; earlier completed barriers remain visible in native status
and the undo journal.

Shape and decoration results contain `{operation,writes}`. Writes count
submitted block assignments, not unique changed cells; native batches can
coalesce repeated positions. Native `status()` and readback receipts are the
authority for online application and partial failures. Random choices use explicit deterministic
`seed` values; otherwise they use the session seed (default 0).
