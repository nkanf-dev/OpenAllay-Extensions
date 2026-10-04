/* Independent, deterministic building presets. No filesystem or command access. */
exports.install = function (api, util) {
    "use strict";
    var own = Object.prototype.hasOwnProperty;
    var directions = ["north", "east", "south", "west"];
    var colors = ["white", "orange", "magenta", "light_blue", "yellow", "lime", "pink", "gray", "light_gray", "cyan", "purple", "blue", "brown", "green", "red", "black"];

    function fail(message) { throw new Error(message); }
    function optionSet(value, allowed) {
        var o = util.options(value), key;
        for (key in o) {
            if (own.call(o, key) && allowed.indexOf(key) < 0) { fail("Unknown preset option: " + key); }
        }
        return o;
    }
    function pick(o, key, fallback) { return o[key] === undefined ? fallback : o[key]; }
    function size(o, shortName, longName, fallback, minimum) {
        if (o[shortName] !== undefined && o[longName] !== undefined && o[shortName] !== o[longName]) {
            fail("Conflicting options: " + shortName + " and " + longName);
        }
        var value = pick(o, shortName, pick(o, longName, fallback));
        value = util.integer(value, longName);
        if (value < minimum) { fail(longName + " must be at least " + minimum); }
        return value;
    }
    function positive(o, key, fallback) {
        var value = util.integer(pick(o, key, fallback), key);
        if (value < 1) { fail(key + " must be positive"); }
        return value;
    }
    function text(o, key, fallback) {
        var value = pick(o, key, fallback);
        if (typeof value !== "string" || value.length === 0) { fail(key + " must be a nonempty string"); }
        return value;
    }
    function doorMaterial(o) {
        var value = text(o, "doorMaterial", "oak");
        if (!/^(?:[a-z0-9_.-]+:[a-z0-9_./-]+|[a-z0-9_]+)$/.test(value)) { fail("doorMaterial must be a material name or a qualified door block ID"); }
        return value;
    }
    function context(x, y, z, o) {
        x = util.integer(x, "x"); y = util.integer(y, "y"); z = util.integer(z, "z");
        var facing = pick(o, "facing", "north"), rotation = directions.indexOf(facing);
        if (rotation < 0) { fail("facing must be north, east, south, or west"); }
        var before = util.count(), bounds = null, world = api.context(), states = Object.create(null);
        var minY = util.integer(world.minY, "context.minY"), maxY = util.integer(world.maxY, "context.maxY");
        if (minY >= maxY) { fail("World height is empty"); }
        function point(u, v, w) {
            var dx = u, dz = w;
            if (rotation === 1) { dx = -w; dz = u; }
            else if (rotation === 2) { dx = -u; dz = -w; }
            else if (rotation === 3) { dx = w; dz = -u; }
            var py = util.integer(y + v, "world y");
            if (py < minY || py >= maxY) { fail("Preset bounds exceed world height"); }
            return {x: util.integer(x + dx, "world x"), y: py, z: util.integer(z + dz, "world z")};
        }
        function touch(p) {
            if (bounds === null) { bounds = {min: {x:p.x, y:p.y, z:p.z}, max: {x:p.x, y:p.y, z:p.z}}; }
            bounds.min.x = Math.min(bounds.min.x, p.x); bounds.max.x = Math.max(bounds.max.x, p.x);
            bounds.min.y = Math.min(bounds.min.y, p.y); bounds.max.y = Math.max(bounds.max.y, p.y);
            bounds.min.z = Math.min(bounds.min.z, p.z); bounds.max.z = Math.max(bounds.max.z, p.z);
        }
        var c = {
            facing: facing,
            point: point,
            state: function (block, properties) {
                // Preserve exact caller IDs and properties. util.state's convenience
                // aliases must not silently turn an unavailable custom ID into another block.
                var exact = typeof block === "string" ? {id:block} : JSON.parse(JSON.stringify(block));
                if (!exact || typeof exact !== "object" || Array.isArray(exact) || typeof exact.id !== "string") {
                    fail("Preset material must be an exact block ID or BlockSpec");
                }
                if (exact.id.indexOf(":") < 0) { exact.id = "minecraft:" + exact.id; }
                if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(exact.id)) { fail("Invalid preset block ID: " + exact.id); }
                var supplied = exact.properties, property;
                if (supplied !== undefined) {
                    if (!supplied || typeof supplied !== "object" || Array.isArray(supplied)) { fail("BlockSpec properties must be an object"); }
                    for (property in supplied) {
                        if (own.call(supplied, property) && typeof supplied[property] !== "string") { fail("BlockSpec properties must have string values"); }
                    }
                }
                var normalized = util.state(exact, properties), key = JSON.stringify(normalized);
                if (!own.call(states, key)) { states[key] = api.transform_state(normalized, rotation * 90, "none"); }
                return states[key];
            },
            material: function (role) {
                var palette = world.materialPalette, material;
                if (!palette || typeof palette !== "object" || Array.isArray(palette) || !own.call(palette, role)) {
                    fail("material_unavailable: native material role '" + role + "' is absent. Inspect context().materialPalette, supply an exact registered state for its preset option, or choose a preset with available roles.");
                }
                material = palette[role];
                if (!material || typeof material !== "object" || Array.isArray(material) || typeof material.id !== "string" || material.id.indexOf(":") < 0) {
                    fail("material_unavailable: native material role '" + role + "' must be a canonical BlockSpec object with a qualified ID. The native adapter must provide a valid registered state.");
                }
                return c.state(material);
            },
            optionState: function (value, role, layoutProperties) {
                if (value === undefined) { return c.material(role); }
                // Only essential layout properties are added to explicit materials.
                // A conflicting user property is an error, never silently replaced.
                var supplied = value && typeof value === "object" ? value.properties : undefined, property;
                if (supplied && layoutProperties) {
                    for (property in layoutProperties) {
                        if (own.call(layoutProperties, property) && own.call(supplied, property) && supplied[property] !== layoutProperties[property]) {
                            fail("Preset material property conflicts with layout: " + property + " must be '" + layoutProperties[property] + "'");
                        }
                    }
                }
                return c.state(value, layoutProperties);
            },
            put: function (u, v, w, state) {
                var p = point(u, v, w); touch(p);
                util.put(p.x, p.y, p.z, state);
            },
            box: function (u1, v1, w1, u2, v2, w2, state) {
                var u, v, w;
                for (v = v1; v <= v2; v++) {
                    for (w = w1; w <= w2; w++) {
                        for (u = u1; u <= u2; u++) { c.put(u, v, w, state); }
                    }
                }
            },
            reserve: function (u1, v1, w1, u2, v2, w2) {
                // Check arithmetic before any write. This is not a size cap.
                point(u1, v1, w1); point(u2, v2, w2);
            },
            flatten: function (u1, w1, u2, w2, surface, underground, depth, clearAbove) {
                var a = point(u1, 0, w1), b = point(u2, 0, w2);
                api.flatten_area(Math.min(a.x,b.x), Math.min(a.z,b.z), Math.max(a.x,b.x), Math.max(a.z,b.z), y,
                    {surface:surface, underground:underground, depth:depth, clearAbove:clearAbove, blendRadius:0});
                touch(point(u1, -depth, w1)); touch(point(u2, clearAbove, w2));
            },
            door: function (u, v, w, material) {
                var p = point(u,v,w); touch(p); touch(point(u,v+1,w));
                api.place_door(p.x,p.y,p.z,{facing:facing,material:material});
            },
            bed: function (u, v, w, color) {
                var p = point(u,v,w); touch(p); touch(point(u,v,w-1));
                api.place_bed(p.x,p.y,p.z,{facing:facing,color:color});
            },
            finish: function (label, metadata) {
                if (bounds !== null) {
                    api.update_connections(bounds.min.x,bounds.min.y,bounds.min.z,bounds.max.x,bounds.max.y,bounds.max.z);
                }
                var result = util.summary(label, before), key;
                result.bounds = bounds;
                if (metadata) { for (key in metadata) { if (own.call(metadata,key)) { result[key] = metadata[key]; } } }
                return result;
            }
        };
        return c;
    }
    function linkedStates(c, material, color) {
        // Native validation happens before clearing terrain or placing supports.
        var door = material.indexOf(":") >= 0 ? material : (/_door$/.test(material) ? material : material + "_door");
        c.state(door, {facing:"north",half:"lower",hinge:"left",open:"false",powered:"false"});
        c.state(door, {facing:"north",half:"upper",hinge:"left",open:"false",powered:"false"});
        if (color !== undefined) {
            if (colors.indexOf(color) < 0) { fail("bedColor must be one of the 16 Minecraft colors"); }
            c.state("minecraft:" + color + "_bed", {facing:"north",part:"foot",occupied:"false"});
            c.state("minecraft:" + color + "_bed", {facing:"north",part:"head",occupied:"false"});
        }
    }
    function shell(c, w, h, d, wall, log, air) {
        c.box(1,1,1,w-2,h,d-2,air);
        c.box(0,1,0,w-1,h,0,wall); c.box(0,1,d-1,w-1,h,d-1,wall);
        c.box(0,1,1,0,h,d-2,wall); c.box(w-1,1,1,w-1,h,d-2,wall);
        c.box(0,1,0,0,h,0,log); c.box(w-1,1,0,w-1,h,0,log);
        c.box(0,1,d-1,0,h,d-1,log); c.box(w-1,1,d-1,w-1,h,d-1,log);
    }
    function pattern(c, w, v, d, first, second) {
        var u, t;
        for (t=0;t<d;t++) { for (u=0;u<w;u++) { c.put(u,v,t,(u+t)%2 === 0 ? first : second); } }
    }
    function windows(c, w, d, h, glass, entrance) {
        var u, t, v = Math.min(2,h-1);
        for (u=2;u<w-2;u+=3) {
            if (u !== entrance) { c.put(u,v,0,glass); }
            c.put(u,v,d-1,glass);
        }
        for (t=2;t<d-2;t+=3) { c.put(0,v,t,glass); c.put(w-1,v,t,glass); }
    }
    function roofStates(c, stair, slab) {
        return {east:c.optionState(stair,"dark_oak_stairs_east",{facing:"east",half:"bottom",shape:"straight"}),
            west:c.optionState(stair,"dark_oak_stairs_west",{facing:"west",half:"bottom",shape:"straight"}),
            ridge:c.optionState(slab,"dark_oak_slab_bottom",{type:"bottom"})};
    }
    function pitched(c, w, h, d, roof, gable) {
        var u,t,v,step,peak = Math.floor((w+1)/2);
        for (u=-1;u<=w;u++) {
            step = Math.min(u+1,w-u);
            for (t=-1;t<=d;t++) { c.put(u,h+1+step,t,step === peak ? roof.ridge : (u < (w-1)/2 ? roof.east : roof.west)); }
            if (u>=0 && u<w) {
                for (v=h+1;v<h+1+step;v++) { c.put(u,v,0,gable); c.put(u,v,d-1,gable); }
            }
        }
    }

    api.build_simple_house = function (x,y,z,options) {
        var o = optionSet(options,["w","width","d","depth","h","height","facing","foundation","wall","log","floor","roof","window","doorMaterial","bedColor"]);
        var w=size(o,"w","width",7,7), d=size(o,"d","depth",7,7), h=size(o,"h","height",5,4);
        var material=doorMaterial(o), color=text(o,"bedColor","red"), c=context(x,y,z,o), entrance=Math.floor((w-1)/2);
        var foundation=c.optionState(o.foundation,"cobblestone"), wall=c.optionState(o.wall,"oak_planks");
        var log=c.optionState(o.log,"oak_log_y",{axis:"y"}), floor=c.optionState(o.floor,"oak_planks");
        var roof=c.optionState(o.roof,"stone_brick_slab_bottom",{type:"bottom"}), glass=c.optionState(o.window,"glass_pane");
        var air=c.material("air"), tableBase=c.material("oak_fence"), tableTop=c.material("oak_pressure_plate_unpowered");
        var furnace=c.material("furnace_north"), chest=c.material("chest_north_single");
        var lantern=c.material("lantern_hanging"), step=c.material("stone_brick_stairs_south");
        linkedStates(c,material,color); c.reserve(-1,-2,-2,w,h+1,d);
        c.box(-1,1,-1,w,h+1,d,air);
        c.box(0,-2,0,w-1,0,d-1,foundation); c.box(0,0,0,w-1,0,d-1,floor);
        shell(c,w,h,d,wall,log,air); windows(c,w,d,h,glass,entrance);
        c.box(-1,h+1,-1,w,h+1,d,roof);
        // The table, storage and two-cell bed occupy disjoint interior cells.
        c.put(2,1,2,tableBase); c.put(2,2,2,tableTop);
        c.put(w-2,1,d-2,furnace); c.put(w-3,1,d-2,chest);
        c.put(entrance,h,Math.floor(d/2),lantern);
        c.put(entrance,-1,-1,foundation); c.put(entrance,0,-1,step);
        c.put(entrance,1,0,air); c.put(entrance,2,0,air);
        c.bed(1,1,d-2,color); c.door(entrance,1,0,material);
        return c.finish("build_simple_house",{width:w,depth:d,height:h,facing:c.facing});
    };

    api.build_skyscraper = function (x,y,z,options) {
        var o=optionSet(options,["w","width","d","depth","floors","floor_h","floorHeight","facing","foundation","foundationDepth","floor","alternateFloor","glass","pier","ceiling","roofWall","antenna","doorMaterial"]);
        var w=size(o,"w","width",15,5), d=size(o,"d","depth",15,5), floors=positive(o,"floors",12);
        var fh=size(o,"floor_h","floorHeight",5,3), foundationDepth=positive(o,"foundationDepth",4), total=util.integer(floors*fh,"total height");
        var material=doorMaterial(o), c=context(x,y,z,o), entrance=Math.floor((w-1)/2), palette=o.glass;
        var glass=[], i,u,v,t,k,level;
        if (palette === undefined) {
            glass=[c.material("light_blue_stained_glass"),c.material("cyan_stained_glass"),c.material("blue_stained_glass")];
        } else {
            if (!Array.isArray(palette) || palette.length === 0) { fail("glass must be a nonempty array of block states"); }
            for (i=0;i<palette.length;i++) { glass.push(c.state(palette[i])); }
        }
        var foundation=c.optionState(o.foundation,"stone_bricks"), first=c.optionState(o.floor,"smooth_stone");
        var second=c.optionState(o.alternateFloor,"polished_andesite"), pier=c.optionState(o.pier,"iron_block");
        var ceiling=c.optionState(o.ceiling,"smooth_stone"), roofWall=c.optionState(o.roofWall,"stone_brick_wall");
        var antenna=c.optionState(o.antenna,"iron_bars"), light=c.material("sea_lantern"), rod=c.material("lightning_rod_up");
        var air=c.material("air"), canopy=c.material("smooth_stone_slab_bottom");
        var step=c.material("stone_brick_stairs_south"), ladder=c.material("ladder_north");
        linkedStates(c,material); c.reserve(0,-foundationDepth,-2,w-1,total+6,d-1);
        c.box(0,1,-2,w-1,total+6,d-1,air); c.box(0,-foundationDepth,0,w-1,0,d-1,foundation);
        for (k=0;k<floors;k++) {
            level=k*fh; pattern(c,w,level,d,first,second);
            for (v=level+1;v<level+fh;v++) {
                c.box(0,v,0,w-1,v,0,glass[k%glass.length]); c.box(0,v,d-1,w-1,v,d-1,glass[k%glass.length]);
                c.box(0,v,1,0,v,d-2,glass[k%glass.length]); c.box(w-1,v,1,w-1,v,d-2,glass[k%glass.length]);
                c.put(0,v,0,pier); c.put(w-1,v,0,pier); c.put(0,v,d-1,pier); c.put(w-1,v,d-1,pier);
            }
            c.box(1,level+fh,1,w-2,level+fh,d-2,ceiling);
        }
        c.box(0,total,0,w-1,total,d-1,ceiling);
        // Install lights after all patterned floors, which are also the ceilings below.
        for (k=1;k<=floors;k++) {
            for (t=2;t<d-1;t+=3) { for (u=2;u<w-1;u+=3) { c.put(u,k*fh,t,light); } }
        }
        c.box(0,total+1,0,w-1,total+1,0,roofWall); c.box(0,total+1,d-1,w-1,total+1,d-1,roofWall);
        c.box(0,total+1,1,0,total+1,d-2,roofWall); c.box(w-1,total+1,1,w-1,total+1,d-2,roofWall);
        // A solid backing keeps the continuous ladder attached through the roof wall.
        c.box(1,1,d-1,1,total+1,d-1,pier);
        for (v=1;v<=total+1;v++) { c.put(1,v,d-2,ladder); }
        c.box(entrance-1,3,-2,entrance+1,3,-1,canopy);
        c.box(entrance-1,-1,-1,entrance+1,-1,-1,foundation); c.box(entrance-1,0,-1,entrance+1,0,-1,step);
        c.box(Math.floor(w/2),total+1,Math.floor(d/2),Math.floor(w/2),total+4,Math.floor(d/2),antenna);
        c.put(Math.floor(w/2),total+5,Math.floor(d/2),rod);
        c.put(entrance,1,0,air); c.put(entrance,2,0,air); c.door(entrance,1,0,material);
        return c.finish("build_skyscraper",{width:w,depth:d,floors:floors,floorHeight:fh,facing:c.facing});
    };

    api.build_cottage = function (x,y,z,options) {
        var o=optionSet(options,["w","width","d","depth","h","height","facing","name","wall","roof_stair","roofStair","roof_slab","roofSlab","log","foundation","floor","alternateFloor","window","doorMaterial","chimney"]);
        var w=size(o,"w","width",8,5), d=size(o,"d","depth",7,5), h=size(o,"h","height",4,3), name=text(o,"name","Cottage");
        var oriented = {}, optionKey;
        for (optionKey in o) { if (own.call(o,optionKey)) { oriented[optionKey] = o[optionKey]; } }
        if (oriented.facing === undefined) { oriented.facing = "south"; }
        if (o.roof_stair !== undefined && o.roofStair !== undefined && o.roof_stair !== o.roofStair) { fail("Conflicting roof_stair and roofStair"); }
        if (o.roof_slab !== undefined && o.roofSlab !== undefined && o.roof_slab !== o.roofSlab) { fail("Conflicting roof_slab and roofSlab"); }
        var material=doorMaterial(o), c=context(x,y,z,oriented), entrance=Math.floor((w-1)/2), peak=h+1+Math.floor((w+1)/2);
        var foundation=c.optionState(o.foundation,"cobblestone"), wall=c.optionState(o.wall,"oak_planks");
        var log=c.optionState(o.log,"oak_log_y",{axis:"y"}), beamX=c.optionState(o.log,"oak_log_x",{axis:"x"}), beamZ=c.optionState(o.log,"oak_log_z",{axis:"z"});
        var floor=c.optionState(o.floor,"spruce_planks"), alternate=c.optionState(o.alternateFloor,"oak_planks"), glass=c.optionState(o.window,"glass_pane");
        var roof=roofStates(c,pick(o,"roof_stair",o.roofStair),pick(o,"roof_slab",o.roofSlab));
        var chimney=c.optionState(o.chimney,"bricks"), campfire=c.material("campfire_lit_north");
        var lantern=c.material("lantern_hanging"), air=c.material("air"), step=c.material("cobblestone_stairs_south");
        linkedStates(c,material); c.reserve(-1,-3,-1,w,peak+4,d);
        c.flatten(-1,-1,w,d,foundation,foundation,3,peak+4); pattern(c,w,0,d,floor,alternate);
        shell(c,w,h,d,wall,log,air);
        c.box(1,h,0,w-2,h,0,beamX); c.box(1,h,d-1,w-2,h,d-1,beamX);
        c.box(0,h,1,0,h,d-2,beamZ); c.box(w-1,h,1,w-1,h,d-2,beamZ);
        windows(c,w,d,h,glass,entrance); pitched(c,w,h,d,roof,wall);
        c.put(1,h,1,beamX); c.put(1,h-1,1,lantern);
        c.box(w-2,h,d-2,w-2,peak+2,d-2,chimney); c.put(w-2,peak+3,d-2,campfire);
        c.put(entrance,0,-1,step); c.put(entrance,1,0,air); c.put(entrance,2,0,air);
        c.door(entrance,1,0,material);
        return c.finish("build_cottage",{name:name,width:w,depth:d,height:h,facing:c.facing});
    };

    api.build_windmill = function (x,y,z,options) {
        var o=optionSet(options,["height","radius","bladeLength","facing","foundation","lowerWall","upperWall","bladeFence","blade","roof","doorMaterial"]);
        var height=positive(o,"height",15), radius=positive(o,"radius",3);
        if (height<5) { fail("height must be at least 5 for a doorway and four clear blades"); }
        if (radius<2) { fail("radius must be at least 2 for a hollow tower and doorway"); }
        var bladeLength=positive(o,"bladeLength",Math.min(radius+2,height-4));
        if (bladeLength>height-4) { fail("bladeLength must leave the lower blade above the entrance (height - 4)"); }
        var material=doorMaterial(o), c=context(x,y,z,o), topRadius=radius-1, hub=height-1;
        var foundation=c.optionState(o.foundation,"cobblestone"), lower=c.optionState(o.lowerWall,"stone_bricks");
        var upper=c.optionState(o.upperWall,"white_concrete"), fence=c.optionState(o.bladeFence,"oak_fence"), wool=c.optionState(o.blade,"white_wool");
        var roof=c.optionState(o.roof,"spruce_planks"), air=c.material("air"), axle=c.material("oak_log_z");
        var step=c.material("stone_brick_stairs_south");
        var extent=Math.max(radius+1,bladeLength+1), maxY=hub+bladeLength+1, u,v,t,r,dist,i,a,b;
        linkedStates(c,material); c.reserve(-extent,-3,-radius-2,extent,Math.max(height+1,maxY),radius+1);
        c.flatten(-extent,-radius-2,extent,radius+1,foundation,foundation,3,Math.max(height+1,maxY));
        for (v=1;v<=height;v++) {
            r=radius-(radius-topRadius)*Math.max(0,v-3)/Math.max(1,height-3);
            for (t=-radius;t<=radius;t++) { for (u=-radius;u<=radius;u++) {
                dist=u*u+t*t;
                if (dist<=r*r) { c.put(u,v,t,dist>(r-1)*(r-1) ? (v<=Math.floor(height/2) ? lower : upper) : air); }
            } }
        }
        for (t=-topRadius;t<=topRadius;t++) { for (u=-topRadius;u<=topRadius;u++) {
            if (u*u+t*t<=topRadius*topRadius) { c.put(u,height+1,t,roof); }
        } }
        c.box(0,hub,-radius-1,0,hub,0,axle);
        var arms=[[1,0],[0,1],[-1,0],[0,-1]];
        for (i=0;i<arms.length;i++) {
            a=arms[i][0]; b=arms[i][1];
            for (t=1;t<=bladeLength;t++) {
                c.put(a*t,hub+b*t,-radius-1,fence);
                if (t>=Math.max(1,Math.ceil(bladeLength/2))) { c.put(a*t-b,hub+b*t+a,-radius-1,wool); }
            }
        }
        c.put(0,0,-radius-1,step); c.put(0,1,-radius,air); c.put(0,2,-radius,air);
        c.door(0,1,-radius,material);
        return c.finish("build_windmill",{height:height,radius:radius,bladeLength:bladeLength,facing:c.facing});
    };

    api.build_farm = function (x,y,z,options) {
        var o=optionSet(options,["w","width","d","depth","crops","facing","fence","gate","foundation"]);
        var w=size(o,"w","width",20,1), d=size(o,"d","depth",16,1), entries=o.crops;
        var c=context(x,y,z,o), crops=[], i,item,block,age,entryKey;
        if (entries === undefined) {
            crops=[c.material("wheat_mature"),c.material("carrots_mature"),c.material("potatoes_mature"),c.material("beetroots_mature")];
        } else {
            if (!Array.isArray(entries) || entries.length===0) { fail("crops must be a nonempty array"); }
            for (i=0;i<entries.length;i++) {
                item=entries[i];
                if (typeof item==="string") { block=item; age=undefined; }
                else if (item && typeof item==="object" && !Array.isArray(item)) {
                    for (entryKey in item) { if (own.call(item,entryKey) && entryKey!=="block" && entryKey!=="age") { fail("Unknown crop field: "+entryKey); } }
                    if (item.block===undefined) { fail("Crop objects require block"); }
                    block=item.block; age=item.age;
                } else { fail("Each crop must be an exact block ID or {block, age}"); }
                if (age === undefined) {
                    // Supplied BlockSpecs keep their age and every other property.
                    // Supplied IDs use the native default, not a guessed mature age.
                    crops.push(c.state(block));
                } else {
                    age=util.integer(age,"crop age");
                    if (age<0) { fail("crop age must be nonnegative"); }
                    if (block && typeof block==="object" && block.properties && block.properties.age!==undefined && block.properties.age!==String(age)) {
                        fail("Conflicting crop age and BlockSpec age");
                    }
                    // The explicit age option alone adds this property. Its allowed
                    // range is checked by the real native registry before any write.
                    crops.push(c.state(block,{age:String(age)}));
                }
            }
        }
        var foundation=c.optionState(o.foundation,"dirt"), farmland=c.material("farmland_hydrated"), water=c.material("water_source");
        var fence=c.optionState(o.fence,"oak_fence"), gate=c.optionState(o.gate,"oak_fence_gate_north",{facing:"north"});
        var air=c.material("air"), entrance=Math.floor((w-1)/2), planted=0,u,t;
        if ((entrance+1)%9===0) { entrance--; } // The gate must lead to soil, not an interior canal.
        c.reserve(-2,-3,-2,w+1,2,d+1); c.flatten(-2,-2,w+1,d+1,foundation,foundation,3,2);
        // An outside canal hydrates even a 1x1 plot. The outer dirt rim contains it.
        for (t=-1;t<=d;t++) { for (u=-1;u<=w;u++) {
            if (u===-1 || u===w || t===-1 || t===d) {
                c.put(u,0,t,water); c.put(u,1,t,fence);
            }
        } }
        c.put(entrance,0,-1,foundation); c.put(entrance,1,-1,gate);
        for (t=0;t<d;t++) { for (u=0;u<w;u++) {
            if ((u+1)%9===0) { c.put(u,0,t,water); c.put(u,1,t,air); }
            else { c.put(u,0,t,farmland); c.put(u,1,t,crops[planted%crops.length]); planted++; }
        } }
        return c.finish("build_farm",{width:w,depth:d,planted:planted,cropTypes:crops.length,facing:c.facing});
    };

    api.build_dock = function (x,y,z,options) {
        var o=optionSet(options,["length","width","facing","deck","log","rail","pilingDepth","pilingSpacing"]);
        var length=positive(o,"length",18), width=positive(o,"width",5), depth=positive(o,"pilingDepth",4), spacing=positive(o,"pilingSpacing",4);
        var c=context(x,y,z,o), deck=c.optionState(o.deck,"spruce_planks"), log=c.optionState(o.log,"spruce_log_y",{axis:"y"});
        var rail=c.optionState(o.rail,"spruce_fence"), lantern=c.material("lantern_standing"), air=c.material("air");
        var left=width>=3 ? 0 : -1, right=width>=3 ? width-1 : width, t,u;
        c.reserve(left,-depth,0,right,3,length-1); c.box(left,1,0,right,3,length-1,air);
        for (t=0;t<length;t++) {
            if (t===0 || t===length-1 || t%spacing===0) {
                c.box(0,-depth,t,0,-1,t,log);
                if (width>1) { c.box(width-1,-depth,t,width-1,-1,t,log); }
                if (width<3) { c.box(left,-depth,t,left,0,t,log); c.box(right,-depth,t,right,0,t,log); }
            }
            for (u=0;u<width;u++) { c.put(u,0,t,deck); }
            c.put(left,1,t,rail); c.put(right,1,t,rail);
        }
        c.put(left,2,length-1,rail); c.put(right,2,length-1,rail);
        c.put(left,3,length-1,lantern); c.put(right,3,length-1,lantern);
        return c.finish("build_dock",{length:length,width:width,facing:c.facing});
    };
};
