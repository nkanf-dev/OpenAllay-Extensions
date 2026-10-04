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
        var before = util.count(), bounds = null, world = api.context();
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
                return api.transform_state(util.state(block, properties), rotation * 90, "none");
            },
            put: function (u, v, w, state) {
                var p = point(u, v, w); touch(p);
                api.place_block(p.x, p.y, p.z, state);
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
        return {east:c.state(stair,{facing:"east",half:"bottom",shape:"straight",waterlogged:"false"}),
            west:c.state(stair,{facing:"west",half:"bottom",shape:"straight",waterlogged:"false"}),
            ridge:c.state(slab,{type:"bottom",waterlogged:"false"})};
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
        var foundation=c.state(pick(o,"foundation","cobblestone")), wall=c.state(pick(o,"wall","oak_planks"));
        var log=c.state(pick(o,"log","oak_log"),{axis:"y"}), floor=c.state(pick(o,"floor","oak_planks"));
        var roof=c.state(pick(o,"roof","stone_brick_slab"),{type:"bottom",waterlogged:"false"}), glass=c.state(pick(o,"window","glass_pane"));
        var air=c.state("air"), tableBase=c.state("oak_fence"), tableTop=c.state("oak_pressure_plate",{powered:"false"});
        var furnace=c.state("furnace",{facing:"north",lit:"false"}), chest=c.state("chest",{facing:"north",type:"single",waterlogged:"false"});
        var lantern=c.state("lantern",{hanging:"true",waterlogged:"false"}), step=c.state("stone_brick_stairs",{facing:"south",half:"bottom",shape:"straight",waterlogged:"false"});
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
        var material=doorMaterial(o), c=context(x,y,z,o), entrance=Math.floor((w-1)/2), palette=pick(o,"glass",["light_blue_stained_glass","cyan_stained_glass","blue_stained_glass"]);
        if (!Array.isArray(palette) || palette.length === 0) { fail("glass must be a nonempty array of block states"); }
        var glass=[], i,u,v,t,k,level;
        for (i=0;i<palette.length;i++) { glass.push(c.state(palette[i])); }
        var foundation=c.state(pick(o,"foundation","stone_bricks")), first=c.state(pick(o,"floor","smooth_stone"));
        var second=c.state(pick(o,"alternateFloor","polished_andesite")), pier=c.state(pick(o,"pier","iron_block"));
        var ceiling=c.state(pick(o,"ceiling","smooth_stone")), roofWall=c.state(pick(o,"roofWall","stone_brick_wall"));
        var antenna=c.state(pick(o,"antenna","iron_bars")), light=c.state("sea_lantern"), rod=c.state("lightning_rod",{facing:"up",powered:"false",waterlogged:"false"});
        var air=c.state("air"), canopy=c.state("smooth_stone_slab",{type:"bottom",waterlogged:"false"});
        var step=c.state("stone_brick_stairs",{facing:"south",half:"bottom",shape:"straight",waterlogged:"false"});
        var ladder=c.state("ladder",{facing:"north",waterlogged:"false"});
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
        var foundation=c.state(pick(o,"foundation","cobblestone")), wall=c.state(pick(o,"wall","oak_planks"));
        var logName=pick(o,"log","oak_log"), log=c.state(logName,{axis:"y"}), beamX=c.state(logName,{axis:"x"}), beamZ=c.state(logName,{axis:"z"});
        var floor=c.state(pick(o,"floor","spruce_planks")), alternate=c.state(pick(o,"alternateFloor","oak_planks")), glass=c.state(pick(o,"window","glass_pane"));
        var roof=roofStates(c,pick(o,"roof_stair",pick(o,"roofStair","dark_oak_stairs")),pick(o,"roof_slab",pick(o,"roofSlab","dark_oak_slab")));
        var chimney=c.state(pick(o,"chimney","bricks")), campfire=c.state("campfire",{facing:"north",lit:"true",signal_fire:"false",waterlogged:"false"});
        var lantern=c.state("lantern",{hanging:"true",waterlogged:"false"}), air=c.state("air");
        var step=c.state("cobblestone_stairs",{facing:"south",half:"bottom",shape:"straight",waterlogged:"false"});
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
        var foundation=c.state(pick(o,"foundation","cobblestone")), lower=c.state(pick(o,"lowerWall","stone_bricks"));
        var upper=c.state(pick(o,"upperWall","white_concrete")), fence=c.state(pick(o,"bladeFence","oak_fence")), wool=c.state(pick(o,"blade","white_wool"));
        var roof=c.state(pick(o,"roof","spruce_planks")), air=c.state("air"), axle=c.state("oak_log",{axis:"z"});
        var step=c.state("stone_brick_stairs",{facing:"south",half:"bottom",shape:"straight",waterlogged:"false"});
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
        var w=size(o,"w","width",20,1), d=size(o,"d","depth",16,1), entries=pick(o,"crops",["wheat","carrots","potatoes","beetroots"]);
        if (!Array.isArray(entries) || entries.length===0) { fail("crops must be a nonempty array"); }
        var c=context(x,y,z,o), crops=[], i,item,block,age,normalized,entryKey;
        var maxAge={"minecraft:wheat":7,"minecraft:carrots":7,"minecraft:potatoes":7,"minecraft:beetroots":3};
        for (i=0;i<entries.length;i++) {
            item=entries[i];
            if (typeof item==="string") { block=item; age=undefined; }
            else if (item && typeof item==="object" && !Array.isArray(item)) {
                for (entryKey in item) { if (own.call(item,entryKey) && entryKey!=="block" && entryKey!=="age") { fail("Unknown crop field: "+entryKey); } }
                if (item.block===undefined) { fail("Crop objects require block"); }
                block=item.block; age=item.age;
            } else { fail("Each crop must be a block name or {block, age}"); }
            normalized=util.state(block);
            if (age===undefined) {
                if (!own.call(maxAge,normalized.id)) { fail("Custom crops require an explicit age validated by the native registry"); }
                age=maxAge[normalized.id];
            }
            age=util.integer(age,"crop age");
            if (age<0 || (own.call(maxAge,normalized.id) && age>maxAge[normalized.id])) { fail("Invalid age for crop "+normalized.id); }
            crops.push(c.state(normalized,{age:String(age)}));
        }
        var foundation=c.state(pick(o,"foundation","dirt")), farmland=c.state("farmland",{moisture:"7"}), water=c.state("water",{level:"0"});
        var fence=c.state(pick(o,"fence","oak_fence")), gate=c.state(pick(o,"gate","oak_fence_gate"),{facing:"north",in_wall:"false",open:"false",powered:"false"});
        var air=c.state("air"), entrance=Math.floor((w-1)/2), planted=0,u,t;
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
        var c=context(x,y,z,o), deck=c.state(pick(o,"deck","spruce_planks")), log=c.state(pick(o,"log","spruce_log"),{axis:"y"});
        var rail=c.state(pick(o,"rail","spruce_fence")), lantern=c.state("lantern",{hanging:"false",waterlogged:"false"}), air=c.state("air");
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
