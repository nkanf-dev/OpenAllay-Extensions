# Structure templates

- `scan_structure(x1,y1,z1,x2,y2,z2,{includeAir:false,metadata:{}})` normalizes
  inclusive bounds and returns a detached palette-deduplicated template.
- `save_template(template,name)` saves under the Extension template store.
- `load_template(name)` loads and validates the versioned template.
- `list_templates()` lists stored templates.
- `paste_structure(template,x,y,z,options?)` uses the input as the minimum
  corner of the transformed structure. `rotation` (or `rotate`) accepts any
  multiple of 90. `mirror` accepts `none`, `front_back` (X reflection), or
  `left_right` (Z reflection); `true` aliases `front_back`.
- `import_legacy_template(object,options?)` converts JSON with `blocks` and
  relative `pos`/`position` or x/y/z fields, block/name/id and properties/props.
  `size` or `dimensions` can supply extents. The original `{size,block_count,
  blocks:[{pos,name,props}],meta}` format is accepted. Legacy metadata is retained.
  Namespace-less IDs are qualified.

The paste transform mirrors first, then rotates clockwise viewed from above.
90° maps `(x,z)` to `(depth-1-z,x)`. 90°/270° swap width and depth. Native
BlockState rotation/mirror handles directional keys, axes, rails, stair shapes,
sign rotation, hinge/handedness, attached directions, and modded behavior.
Typed block-entity SNBT is passed through the native transform and placement
rewrites position. Nonidentity transforms of unsupported opaque or directional
block-entity payloads fail explicitly before paste writes; the module never
pretends that copying unknown SNBT also rotates its contents.

Default scan omits air, making paste an overlay. A scan with `includeAir:true`
preserves explicit air; paste clears those cells unless `includeAir:false`.
`replace:true` additionally clears missing cells inside the transformed box.
A `structure_void` cell always preserves its target cell, even in replace mode.
Missing, explicit air and structure_void are distinct. Non-block entities are
not duplicated.

By default dataVersion must match the live world. `allowVersionMismatch:true`
requests native validation of existing states, not automatic data migration.
`updateConnections:false` lets a larger composed build defer native updates.
Paste journals direct assignments and explicit shape repairs, not later physics
cascades. It is not an atomic world transaction. See [execution](execution.md)
for conflict and uncertain-intent handling during undo.

Schema version 1:
```json
{"format":"openallay:structure","version":1,"size":[2,1,1],
 "includesAir":false,"gameVersion":"26.2","dataVersion":0,
 "palette":[{"id":"minecraft:chest","properties":{"facing":"north"}}],
 "blocks":[{"pos":[0,0,0],"state":0,"blockEntity":"{Items:[]}"}],
 "metadata":{}}
```
The example dataVersion is illustrative; use the actual native context value.
Template names are single names containing letters, numbers, dot, underscore
or hyphen, starting with a letter or number. Filesystem paths and traversal
are rejected. The native store performs atomic writes and validates corruption,
size, duplicate cells, palette indices and schema versions on load.
