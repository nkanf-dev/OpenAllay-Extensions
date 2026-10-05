---
name: minecraft-builder
description: Use when building structures, shaping terrain, laying paths, decorating, or copying structures in the current Minecraft world.
metadata:
  openallay/version: "0.4.0"
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

The Extension uses SDK 0.4.0 and Java 8. Its support manifest lists the exact
Minecraft and loader targets. The connected native registry and
`context().materialPalette` define available preset materials.
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

Enabling Minecraft Builder enables its building operations. Normal restricted
JavaScript can open, read and build in the active integrated-server world
without JVM access. Native methods enforce the active invocation, exact session,
loaded chunks and world lifetime. Nothing edits offline saves. Use the returned
native status to distinguish completed writes, cancellation, partial failure
and undo results.
