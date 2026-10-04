---
name: minecraft-builder
description: Use when building structures, shaping terrain, laying paths, decorating, or copying structures in the current Minecraft world.
metadata:
  openallay/version: "0.3.0"
  openallay/requires-capabilities: "openallay_builder:world_write"
  openallay/requires-extensions: "openallay:builder"
allowed-tools: "openallay:run_javascript"
---
# Minecraft builder

Load the reviewed module with `require("openallay_builder:building")`.
Open a live session, build with its methods, and return the result needed for
the answer plus native status. Use `batch` for custom write loops, `read_region`
for contiguous reads, and `get_blocks` for selected positions. The Extension
supplies native block validation, world-thread writes, connection updates,
typed block entities, templates, and undo journals.

The shared source targets Extension SDK 0.3 and Java 8. Minecraft 26.2 is the
intended native candidate, not a validated release claim. This source does not
establish support for other game versions or loaders. The connected native
registry and `context().materialPalette` define available preset materials.
A missing role fails as `material_unavailable` before that preset writes.
Use exact registered IDs/properties for custom preset materials; do not invent
aliases or drop unavailable properties.

```js
const building = require("openallay_builder:building");
const b = building.open({seed: 17});
const p = b.get_player_pos();
const result = b.build_cottage(Math.floor(p.x) + 4, Math.floor(p.y) - 1,
  Math.floor(p.z) + 4, {facing: "south"});
return {result, status: b.finish()};
```

Load a reference when its operation details are needed:
[API](references/api.md) for blocks and setup;
[geometry](references/geometry.md) for shapes;
[decoration](references/decoration.md) for linked blocks and trees;
[terrain](references/terrain.md) for scans, leveling, and A* paths;
[presets](references/presets.md) for all six furnished builds;
[templates](references/templates.md) for copying and persistence;
and [execution](references/execution.md) for online lifecycle and undo.

Requirements metadata is advisory. Normal JavaScript can open and read the
active integrated-server world without JVM access. World-changing methods need
the separate Builder world-write grant; installation and read access do not
provide it. Actual methods enforce invocation lifetime and write authorization.
Nothing edits offline saves. Distinguish completed writes, cancellation, partial failure, and undo
results using the returned native status.
