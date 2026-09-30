# Decoration

All positions are integer block coordinates. Place supports before linked or
supported decorations. Native batch placement installs paired blocks before
neighborhood recomputation.

- `place_door(x,y,z,{material:"oak",facing:"north",hinge:"left",open:false})`:
  lower half at y, upper at y+1. Material accepts species, `oak_door`, or a
  namespaced door ID. All four cardinal facings and both hinges are supported.
- `place_bed(x,y,z,{facing:"north",color:"red",block?})`: foot at the input;
  head one block in the facing direction. `block` overrides the color ID.
- `place_windows(x1,y1,z1,x2,y2,z2,{spacing:3,block:"glass_pane"})`: places
  windows on all four walls and every Y in the interval. Corners stay intact.
  `glass` aliases `block`. Narrow one-cell dimensions have no interior windows.
- `place_lantern_post(x,y,z,{height:3,post:"oak_fence",lantern:"lantern"})`:
  fence starts at y; the standing lantern is at y+height. Supply support at y-1.
- `place_tree(x,y,z,{trunk:"oak_log",leaves:"oak_leaves",trunk_h?,radius:2,seed?})`:
  y is the first trunk cell. Default seeded trunk height is 4..6; `height`
  aliases `trunk_h`. Leaves are persistent. Crown placement cannot overwrite
  trunk cells. Custom trunk/leaves IDs use native validation.
- `place_flower(x,y,z,{potted:false,block?,seed?})`: explicit block or seeded
  selection from `FLOWERS`/`POTTED_FLOWERS`.

Call `update_connections` after the composed build to apply native fence/pane,
door, stair and support behavior. A failed native batch reports partial journal
state; do not infer all-or-nothing success from a thrown error.
