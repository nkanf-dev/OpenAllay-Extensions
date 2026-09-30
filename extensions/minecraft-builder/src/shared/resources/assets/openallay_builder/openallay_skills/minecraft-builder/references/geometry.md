# Geometry

All bounds are inclusive and accept either endpoint order. Materials are an ID
or `{id,properties,blockEntity?}`. Functions return `{operation,writes}`.

| Method | Definition and options |
|---|---|
| `build_box(x1,y1,z1,x2,y2,z2,block,options?)` | Solid by default. `hollow:true` keeps all six boundary faces; interior becomes air unless `clearInterior:false`. `properties` merges into the material. |
| `build_walls(x1,y1,z1,x2,y2,z2,block,options?)` | Four vertical boundary walls, no floor/ceiling. `corner` selects corner columns. `properties` applies to walls. |
| `build_floor(x1,y,z1,x2,z2,block,alternate?)` | Filled rectangle. Alternate material occupies odd world-coordinate `x+z` parity, including negative coordinates. |
| `build_circle(cx,y,cz,radius,block,options?)` | Integer disk `dx²+dz² <= radius²` when `filled:true` (`fill` alias). Default ring is its four-neighbor boundary. Radius 0 writes one block. |
| `build_cylinder(cx,y,cz,radius,height,block,options?)` | Positive number of stacked disks. `hollow:true` selects rings; `clearInterior:true` clears cells inside them. |
| `build_cone(cx,y,cz,radius,height,block,options?)` | Disk radius at layer k is `floor(radius*(height-1-k)/(height-1))`; top terminates at radius 0. Height 1 is the base disk. `hollow` and `clearInterior` apply per layer. |
| `build_arch(x,y,z1,z2,height,block,options?)` | Upper semielliptical arch spanning z1..z2 at fixed x. `axis:"x"` instead spans X at fixed Z=x. `thickness` is a positive vertical band (default 1). |
| `build_pitched_roof(x1,y,z1,x2,z2,stair,slab,options?)` | Two sloping stair planes. `axis:"z"` (default) means ridge along Z; `axis:"x"` along X. Odd cross-spans use a bottom-slab ridge. Even spans meet with two facing stair rows. `overhang` expands normalized X/Z bounds. |

```js
b.build_box(-4, 64, -4, 4, 69, 4, "stone_bricks", {hollow:true});
b.build_pitched_roof(-4, 70, -4, 4, 4, "spruce_stairs", "spruce_slab",
  {axis:"z", overhang:1});
b.update_connections(-5,64,-5,5,75,5);
```

There is no fixed build-size cap. Current dimension height and native world
availability still determine valid coordinates. Explicitly invoke connection
updates after composing dependent geometry so intermediate linked blocks are
not removed before their counterparts exist.
