---
name: minecraft-builder
description: Use when building structures, shaping terrain, laying paths, decorating, or copying structures in the current Minecraft world.
metadata:
  openallay/version: "0.1.0"
  openallay/requires-capabilities: "unrestricted-javascript"
  openallay/requires-extensions: "openallay:builder"
allowed-tools: "openallay:run_javascript"
---
# Minecraft builder

Load the reviewed module with `require("openallay_builder:building")`.
Open a live session, build with its methods, and return a short result plus
`status()`. The Extension supplies native block validation, world-thread writes,
connection updates, typed block entities, templates, and undo journals.

```js
const building = require("openallay_builder:building");
const b = building.open({seed: 17});
const p = b.get_player_pos();
const result = b.build_cottage(Math.floor(p.x) + 4, Math.floor(p.y) - 1,
  Math.floor(p.z) + 4, {facing: "south"});
return {result, status: b.finish()};
```

Use [API](references/api.md) for blocks and setup;
[geometry](references/geometry.md) for shapes;
[decoration](references/decoration.md) for linked blocks and trees;
[terrain](references/terrain.md) for scans, leveling, and A* paths;
[presets](references/presets.md) for all six furnished builds;
[templates](references/templates.md) for copying and persistence;
and [execution](references/execution.md) for online lifecycle and undo.

Requirements metadata is advisory. Actual session opening reports whether the
live world and required runtime access are available. Nothing edits offline
saves. Distinguish completed writes, cancellation, partial failure, and undo
results using the returned native status.
