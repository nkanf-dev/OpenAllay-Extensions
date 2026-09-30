"use strict";
// Independently implemented online construction algorithms. No offline save access.
var ALIASES = {tulip_red:"red_tulip",tulip_orange:"orange_tulip",tulip_pink:"pink_tulip",tulip_white:"white_tulip",oak_plank:"oak_planks",spruce_plank:"spruce_planks",birch_plank:"birch_planks",dark_oak_plank:"dark_oak_planks",stone_brick:"stone_bricks",cobble:"cobblestone",wood:"oak_planks"};
function qualify(id) {
    if (typeof id !== "string" || !id.length) throw new Error("block ID must be a nonempty string");
    if (id.indexOf(":") < 0) id = "minecraft:" + (ALIASES[id] || id);
    if (!/^[a-z0-9_.-]+:[a-z0-9_./-]+$/.test(id)) throw new Error("invalid block ID: " + id);
    return id;
}
function integer(n, name) { if (typeof n !== "number" || !isFinite(n) || Math.floor(n) !== n || Math.abs(n) > 2147483647) throw new Error((name || "coordinate") + " must be a finite 32-bit integer"); return n; }
function positive(n,name) { integer(n,name); if(n<1) throw new Error(name+" must be positive"); return n; }
function nonnegative(n,name) { integer(n,name); if(n<0) throw new Error(name+" must be nonnegative"); return n; }
function options(o) { if(o===undefined || o===null) return {}; if(typeof o!=="object" || Array.isArray(o)) throw new Error("options must be an object"); return o; }
function decode(v) { return typeof v === "string" || (v !== null && typeof v === "object" && typeof v.charAt === "function") ? JSON.parse(String(v)) : v; }
function clone(v) { return JSON.parse(JSON.stringify(v)); }
function state(block,props) {
    var s = typeof block === "string" ? {id:qualify(block),properties:{}} : clone(block);
    if(!s || typeof s!=="object") throw new Error("block must be an ID or BlockSpec");
    s.id=qualify(s.id); s.properties=options(s.properties); var p={},k;
    for(k in s.properties) if(Object.prototype.hasOwnProperty.call(s.properties,k)) p[k]=String(s.properties[k]);
    props=options(props); for(k in props) if(Object.prototype.hasOwnProperty.call(props,k)) p[k]=String(props[k]);
    s.properties=p;
    if(s.blockEntity!==undefined && typeof s.blockEntity!=="string") throw new Error("blockEntity must be typed SNBT text");
    return s;
}
function rng(seed) { var t=2166136261,i,s=String(seed===undefined?0:seed); for(i=0;i<s.length;i++) { t^=s.charCodeAt(i); t=(t*16777619)>>>0; } if(!t)t=1; return function(){ t^=t<<13;t^=t>>>17;t^=t<<5;return (t>>>0)/4294967296; }; }
function bounds(x1,y1,z1,x2,y2,z2) {
    integer(x1);integer(y1);integer(z1);integer(x2);integer(y2);integer(z2);
    return {minX:Math.min(x1,x2),minY:Math.min(y1,y2),minZ:Math.min(z1,z2),maxX:Math.max(x1,x2),maxY:Math.max(y1,y2),maxZ:Math.max(z1,z2)};
}
var AIR={id:"minecraft:air",properties:{}};
function isAir(s) { return s.id==="minecraft:air" || s.id==="minecraft:cave_air" || s.id==="minecraft:void_air"; }
function qualifiedList(s) { return s.split(" ").map(qualify); }
var FLOWERS=qualifiedList("poppy dandelion blue_orchid allium azure_bluet cornflower lily_of_the_valley oxeye_daisy red_tulip orange_tulip pink_tulip white_tulip");
var POTTED=qualifiedList("potted_poppy potted_dandelion potted_blue_orchid potted_allium potted_fern potted_azure_bluet");
var GROUND=qualifiedList("andesite basalt bedrock blackstone blue_ice calcite clay coarse_dirt deepslate diorite dirt end_stone granite grass_block gravel ice moss_block mud mycelium netherrack packed_ice podzol red_sand red_sandstone sand sandstone snow_block soul_sand soul_soil stone terracotta tuff");
var BUILDING=qualifiedList("acacia_leaves acacia_log acacia_planks acacia_stairs azalea_leaves bamboo_planks barrel birch_door birch_fence birch_leaves birch_log birch_planks birch_slab birch_stairs birch_wood bookshelf brick_slab brick_stairs bricks campfire cherry_log cherry_planks chest cobblestone cobblestone_slab cobblestone_stairs cracked_stone_bricks crafting_table crimson_planks cyan_stained_glass dark_oak_door dark_oak_fence dark_oak_leaves dark_oak_log dark_oak_planks dark_oak_slab dark_oak_stairs dark_oak_wood deepslate_bricks diamond_block furnace glass glass_pane glowstone gold_block iron_block iron_door jungle_leaves jungle_log jungle_planks jungle_stairs ladder lantern light_blue_stained_glass light_gray_concrete light_gray_stained_glass light_gray_terracotta light_gray_wool mangrove_log mangrove_planks mossy_cobblestone mossy_stone_bricks oak_door oak_fence oak_fence_gate oak_leaves oak_log oak_planks oak_slab oak_stairs oak_wood orange_stained_glass orange_terracotta polished_andesite polished_blackstone polished_blackstone_bricks polished_deepslate polished_diorite polished_granite quartz_block quartz_pillar sandstone_stairs scaffolding sea_lantern smooth_quartz smooth_stone smooth_stone_slab spruce_door spruce_fence spruce_fence_gate spruce_leaves spruce_log spruce_planks spruce_slab spruce_stairs spruce_wood stone_brick_slab stone_brick_stairs stone_bricks stone_slab stone_stairs stripped_acacia_log stripped_birch_log stripped_dark_oak_log stripped_jungle_log stripped_oak_log stripped_spruce_log torch wall_torch warped_planks white_concrete white_stained_glass white_terracotta white_wool");
exports.BLOCK_ALIASES=clone(ALIASES);exports.FLOWERS=FLOWERS.slice();exports.POTTED_FLOWERS=POTTED.slice();exports.NATURAL_GROUND=GROUND.slice();exports.BUILDING_BLOCKS=BUILDING.slice();exports._fix_block_name=qualify;
exports.create=function(backend,settings) {
    settings=options(settings); if(!backend || typeof backend.read!=="function" || typeof backend.write!=="function") throw new Error("backend requires read and write");
    var api={},written=0,random=rng(settings.seed),closed=false,heightContext=null,pending=[],batchDepth=0;
    function flush(){if(!pending.length)return;var changes=pending;pending=[];requireMethod("writeRegion").call(backend,JSON.stringify(changes));}

    function requireMethod(name) { if(typeof backend[name]!=="function") throw new Error("backend does not support "+name); return backend[name]; }
    function context() { var c=decode(requireMethod("context").call(backend)); integer(c.minY,"minY");integer(c.maxY,"maxY");if(c.maxY<=c.minY)throw new Error("invalid world height");return c; }
    function pos(x,y,z) { if(closed)throw new Error("builder session is closed");integer(x,"x");integer(y,"y");integer(z,"z"); var c=heightContext||(heightContext=context()); if(y<c.minY||y>=c.maxY)throw new Error("Y outside current dimension build height"); }
    function summary(label,before) { return {operation:label,writes:written-before}; }
    function put(x,y,z,s) { pos(x,y,z);s=state(s);if(batchDepth>0&&typeof backend.writeRegion==="function"){pending.push({x:x,y:y,z:z,state:s});written++;if(pending.length>=256)flush();}else{backend.write(x,y,z,JSON.stringify(s));written++;} }
    function batch(changes) {
        flush();var i;for(i=0;i<changes.length;i++){pos(changes[i].x,changes[i].y,changes[i].z);changes[i].state=state(changes[i].state);}
        if(typeof backend.writeRegion==="function") { var result=decode(backend.writeRegion(JSON.stringify(changes)));written+=changes.length;return result; }
        var before=[];for(i=0;i<changes.length;i++)before.push(api.get_block_full(changes[i].x,changes[i].y,changes[i].z));
        try { for(i=0;i<changes.length;i++)put(changes[i].x,changes[i].y,changes[i].z,changes[i].state); }
        catch(error) { var restoration=[];for(var j=i-1;j>=0;j--)try{backend.write(changes[j].x,changes[j].y,changes[j].z,JSON.stringify(before[j]));}catch(e){restoration.push(String(e));} throw new Error(String(error)+(restoration.length?"; rollback incomplete: "+restoration.join("; "):"; batch restored")); }
    }
    function checkBounds(b) { pos(b.minX,b.minY,b.minZ);pos(b.maxX,b.maxY,b.maxZ);return b; }
    api.context=context;
    api.get_block_full=function(x,y,z) { flush();pos(x,y,z);var s=decode(backend.read(x,y,z));if(!s||s.unknown||s.loaded===false)throw new Error("block is unobserved at "+[x,y,z]);return state(s); };
    api.get_block=function(x,y,z) { return api.get_block_full(x,y,z).id; };
    api.place_block=function(x,y,z,block,props) { var before=written;put(x,y,z,state(block,props));return summary("place_block",before); };
    api.transform_state=function(block,rotation,mirror) { rotation=rotation===undefined?0:integer(rotation,"rotation");if(rotation%90!==0)throw new Error("rotation must be a multiple of 90");rotation=((rotation%360)+360)%360;mirror=mirror===true?"front_back":(mirror||"none");if(["none","left_right","front_back"].indexOf(mirror)<0)throw new Error("invalid mirror");return state(decode(requireMethod("transformState").call(backend,JSON.stringify(state(block)),rotation,mirror))); };
    api.update_connections=function(x1,y1,z1,x2,y2,z2) { flush();var b=checkBounds(bounds(x1,y1,z1,x2,y2,z2));return decode(requireMethod("updateConnections").call(backend,JSON.stringify(b))); };
    api.status=function(){return decode(requireMethod("status").call(backend));};
    api.cancel=function(){requireMethod("cancel").call(backend);return api.status();};
    api.finish=function(){return decode(requireMethod("finish").call(backend));};
    api.list_operations=function(){return decode(requireMethod("listOperations").call(backend));};
    api.close=function(){requireMethod("close").call(backend);closed=true;return api.status();};
    api.undo=function(id){var method=requireMethod("undo");return decode(id===undefined?method.call(backend):method.call(backend,String(id)));};
    api.get_player_pos=function(){return clone(context().player);};api.detect_version=function(){return context().version;};api.save_and_close=api.close;
    api.BLOCK_ALIASES=clone(ALIASES);api.FLOWERS=FLOWERS.slice();api.POTTED_FLOWERS=POTTED.slice();api.NATURAL_GROUND=GROUND.slice();api.BUILDING_BLOCKS=BUILDING.slice();api._fix_block_name=qualify;
    api.build_box=function(x1,y1,z1,x2,y2,z2,block,o) {
        o=options(o);var b=checkBounds(bounds(x1,y1,z1,x2,y2,z2)),s=state(block,o.properties),before=written;
        for(var x=b.minX;x<=b.maxX;x++)for(var y=b.minY;y<=b.maxY;y++)for(var z=b.minZ;z<=b.maxZ;z++) {
            var edge=x===b.minX||x===b.maxX||y===b.minY||y===b.maxY||z===b.minZ||z===b.maxZ;
            if(!o.hollow||edge)put(x,y,z,s);else if(o.clearInterior!==false)put(x,y,z,AIR);
        }return summary("build_box",before);
    };
    api.build_walls=function(x1,y1,z1,x2,y2,z2,block,o) {
        o=options(o);var b=checkBounds(bounds(x1,y1,z1,x2,y2,z2)),s=state(block,o.properties),corner=state(o.corner||s),before=written;
        for(var x=b.minX;x<=b.maxX;x++)for(var z=b.minZ;z<=b.maxZ;z++)if(x===b.minX||x===b.maxX||z===b.minZ||z===b.maxZ)for(var y=b.minY;y<=b.maxY;y++)put(x,y,z,((x===b.minX||x===b.maxX)&&(z===b.minZ||z===b.maxZ))?corner:s);
        return summary("build_walls",before);
    };
    api.build_floor=function(x1,y,z1,x2,z2,block,alternate) {
        var b=checkBounds(bounds(x1,y,z1,x2,y,z2)),s=state(block),t=alternate===undefined?null:state(alternate),before=written;
        for(var x=b.minX;x<=b.maxX;x++)for(var z=b.minZ;z<=b.maxZ;z++)put(x,y,z,t&&((x+z)%2!==0)?t:s);return summary("build_floor",before);
    };
    function disk(r,x,z){return x*x+z*z<=r*r;}
    function ring(r,x,z){return disk(r,x,z)&&(!disk(r,x+1,z)||!disk(r,x-1,z)||!disk(r,x,z+1)||!disk(r,x,z-1));}
    api.build_circle=function(cx,y,cz,radius,block,o) {
        o=options(o);nonnegative(radius,"radius");checkBounds(bounds(cx-radius,y,cz-radius,cx+radius,y,cz+radius));var s=state(block,o.properties),before=written;
        for(var x=-radius;x<=radius;x++)for(var z=-radius;z<=radius;z++)if((o.filled||o.fill)?disk(radius,x,z):ring(radius,x,z))put(cx+x,y,cz+z,s);return summary("build_circle",before);
    };
    api.build_cylinder=function(cx,y,cz,radius,height,block,o) {
        o=options(o);positive(height,"height");nonnegative(radius,"radius");checkBounds(bounds(cx-radius,y,cz-radius,cx+radius,y+height-1,cz+radius));var s=state(block,o.properties),before=written;
        for(var dy=0;dy<height;dy++)for(var dx=-radius;dx<=radius;dx++)for(var dz=-radius;dz<=radius;dz++)if(disk(radius,dx,dz)){if(!o.hollow||ring(radius,dx,dz))put(cx+dx,y+dy,cz+dz,s);else if(o.clearInterior)put(cx+dx,y+dy,cz+dz,AIR);}
        return summary("build_cylinder",before);
    };
    api.build_cone=function(cx,y,cz,radius,height,block,o) {
        o=options(o);positive(height,"height");nonnegative(radius,"radius");checkBounds(bounds(cx-radius,y,cz-radius,cx+radius,y+height-1,cz+radius));var before=written;
        for(var dy=0;dy<height;dy++){var r=height===1?radius:Math.floor(radius*(height-1-dy)/(height-1));api.build_cylinder(cx,y+dy,cz,r,1,block,{hollow:!!o.hollow,clearInterior:!!o.clearInterior,properties:o.properties});}return summary("build_cone",before);
    };
    api.build_arch=function(x,y,z1,z2,height,block,o) {
        o=options(o);positive(height,"height");var axis=o.axis||"z";if(axis!=="x"&&axis!=="z")throw new Error("arch axis must be x or z");integer(x);integer(y);integer(z1);integer(z2);
        var lo=Math.min(z1,z2),hi=Math.max(z1,z2),span=hi-lo,s=state(block,o.properties),before=written,thickness=positive(o.thickness===undefined?1:o.thickness,"thickness"),points={};
        function archPut(u,v){var px=axis==="z"?x:u,pz=axis==="z"?u:x;pos(px,v,pz);points[[px,v,pz].join(",")]=[px,v,pz];}
        function archTop(u){var n=span===0?0:(2*(u-lo)/span-1);return y+Math.round((height-1)*Math.sqrt(Math.max(0,1-n*n)));}
        for(var u=lo;u<=hi;u++) {var top=archTop(u),bottom=top-thickness+1;if(u>lo)bottom=Math.min(bottom,archTop(u-1)+1);if(u<hi)bottom=Math.min(bottom,archTop(u+1)+1);for(var v=Math.max(y,bottom);v<=top;v++)archPut(u,v);}
        var ks=Object.keys(points);for(var i=0;i<ks.length;i++){var p=points[ks[i]];put(p[0],p[1],p[2],s);}return summary("build_arch",before);
    };
    api.build_pitched_roof=function(x1,y,z1,x2,z2,stair,slab,o) {
        o=options(o);var axis=o.axis||"z";if(axis!=="x"&&axis!=="z")throw new Error("roof axis must be x or z");var over=nonnegative(o.overhang===undefined?0:o.overhang,"overhang"),baseBounds=bounds(x1,y,z1,x2,y,z2),b=bounds(baseBounds.minX-over,y,baseBounds.minZ-over,baseBounds.maxX+over,y,baseBounds.maxZ+over),span=axis==="z"?b.maxX-b.minX+1:b.maxZ-b.minZ+1;
        checkBounds(bounds(b.minX,y,b.minZ,b.maxX,y+Math.floor((span-1)/2),b.maxZ));var ss=state(stair),sl=state(slab),before=written;
        for(var x=b.minX;x<=b.maxX;x++)for(var z=b.minZ;z<=b.maxZ;z++) {var i=axis==="z"?x-b.minX:z-b.minZ,dy=Math.min(i,span-1-i),ridge=span%2===1&&i===(span-1)/2,facing=axis==="z"?(i<span/2?"east":"west"):(i<span/2?"south":"north");put(x,y+dy,z,ridge?state(sl,{type:"bottom"}):state(ss,{facing:facing,half:"bottom",shape:"straight"}));}return summary("build_pitched_roof",before);
    };

    function facing(value) { var f=value||"north";if(["north","east","south","west"].indexOf(f)<0)throw new Error("facing must be north, east, south or west");return f; }
    function facingStep(f){return {north:[0,-1],east:[1,0],south:[0,1],west:[-1,0]}[f];}
    api.place_door=function(x,y,z,o) {
        o=options(o);var f=facing(o.facing),material=o.material||"oak",id=material.indexOf(":")>=0?material:(/_door$/.test(material)?material:material+"_door"),before=written;
        var base=state(id,{facing:f,hinge:o.hinge||"left",open:o.open===true,powered:false});if(["left","right"].indexOf(base.properties.hinge)<0)throw new Error("invalid hinge");
        batch([{x:x,y:y,z:z,state:state(base,{half:"lower"})},{x:x,y:y+1,z:z,state:state(base,{half:"upper"})}]);return summary("place_door",before);
    };
    api.place_bed=function(x,y,z,o) {
        o=options(o);var f=facing(o.facing),delta=facingStep(f),base=state(o.block||(o.color||"red")+"_bed",{facing:f,occupied:false}),before=written;
        batch([{x:x,y:y,z:z,state:state(base,{part:"foot"})},{x:x+delta[0],y:y,z:z+delta[1],state:state(base,{part:"head"})}]);return summary("place_bed",before);
    };
    api.place_windows=function(x1,y1,z1,x2,y2,z2,o) {
        o=options(o);var b=checkBounds(bounds(x1,y1,z1,x2,y2,z2)),spacing=positive(o.spacing===undefined?3:o.spacing,"spacing"),s=state(o.block||o.glass||"glass_pane"),before=written;
        for(var y=b.minY;y<=b.maxY;y++){for(var x=b.minX+1;x<b.maxX;x+=spacing){put(x,y,b.minZ,s);if(b.maxZ!==b.minZ)put(x,y,b.maxZ,s);}for(var z=b.minZ+1;z<b.maxZ;z+=spacing){put(b.minX,y,z,s);if(b.maxX!==b.minX)put(b.maxX,y,z,s);}}return summary("place_windows",before);
    };
    api.place_lantern_post=function(x,y,z,o) {
        o=options(o);var h=positive(o.height===undefined?3:o.height,"height"),before=written;checkBounds(bounds(x,y,z,x,y+h,z));var fence=state(o.post||"oak_fence"),lantern=state(o.lantern||"lantern",{hanging:false});
        for(var i=0;i<h;i++)put(x,y+i,z,fence);put(x,y+h,z,lantern);return summary("place_lantern_post",before);
    };
    api.place_tree=function(x,y,z,o) {
        o=options(o);var rand=o.seed===undefined?random:rng(o.seed),h=positive(o.trunk_h===undefined?(o.height===undefined?4+Math.floor(rand()*3):o.height):o.trunk_h,"trunk height"),radius=positive(o.radius===undefined?2:o.radius,"crown radius"),before=written;
        checkBounds(bounds(x-radius,y,z-radius,x+radius,y+h+1,z+radius));var trunk=state(o.trunk||"oak_log",o.trunkProperties),leaves=state(o.leaves||"oak_leaves",{persistent:true,distance:1});
        // Crown first, then trunk: no leaf write can replace a trunk cell.
        for(var dy=(h===1?0:-1);dy<=2;dy++){var r=dy===2?Math.max(0,radius-1):radius;for(var dx=-r;dx<=r;dx++)for(var dz=-r;dz<=r;dz++){if(dx===0&&dz===0&&dy<1)continue;if(Math.abs(dx)===r&&Math.abs(dz)===r&&r>0&&rand()<0.35)continue;put(x+dx,y+h-1+dy,z+dz,leaves);}}
        for(var k=0;k<h;k++)put(x,y+k,z,trunk);return summary("place_tree",before);
    };
    api.place_flower=function(x,y,z,o) {o=options(o);var rand=o.seed===undefined?random:rng(o.seed),palette=o.potted?POTTED:FLOWERS,id=o.block||palette[Math.floor(rand()*palette.length)];return api.place_block(x,y,z,id);};
    function templateName(name) {if(typeof name!=="string"||!/^[a-zA-Z0-9][a-zA-Z0-9_.-]*$/.test(name)||name==="."||name==="..")throw new Error("invalid template name");return name;}
    function canonicalState(s){var p={},keys=Object.keys(s.properties).sort();for(var i=0;i<keys.length;i++)p[keys[i]]=s.properties[keys[i]];return {id:s.id,properties:p};}
    function validateTemplate(t) {
        var i,j;t=clone(t);if(t.format!=="openallay:structure"||t.version!==1)throw new Error("unsupported template format/version");
        if(!Array.isArray(t.size)||t.size.length!==3)throw new Error("template size must contain three dimensions");for(i=0;i<3;i++)positive(t.size[i],"template size");
        if(typeof t.includesAir!=="boolean"||typeof t.gameVersion!=="string"||!t.gameVersion)throw new Error("template requires includesAir and gameVersion");nonnegative(t.dataVersion,"dataVersion");
        t.metadata=options(t.metadata);
        if(!Array.isArray(t.palette)||!Array.isArray(t.blocks))throw new Error("template requires palette and blocks");for(i=0;i<t.palette.length;i++){t.palette[i]=state(t.palette[i]);if(t.palette[i].blockEntity!==undefined)throw new Error("block entities belong to cells, not palette entries");}
        var seen={};for(i=0;i<t.blocks.length;i++){var b=t.blocks[i];if(!Array.isArray(b.pos)||b.pos.length!==3)throw new Error("invalid template position");for(j=0;j<3;j++){nonnegative(b.pos[j],"relative coordinate");if(b.pos[j]>=t.size[j])throw new Error("template position outside size");}integer(b.state,"palette index");if(b.state<0||b.state>=t.palette.length)throw new Error("invalid palette index");if(!t.includesAir&&isAir(t.palette[b.state]))throw new Error("air cell conflicts with includesAir=false");var key=b.pos.join(",");if(seen[key])throw new Error("duplicate template position");seen[key]=true;if(b.blockEntity!==undefined&&typeof b.blockEntity!=="string")throw new Error("blockEntity must be SNBT");}
        return t;
    }
    api.scan_structure=function(x1,y1,z1,x2,y2,z2,o) {
        o=options(o);var b=checkBounds(bounds(x1,y1,z1,x2,y2,z2)),c=context(),t={format:"openallay:structure",version:1,size:[b.maxX-b.minX+1,b.maxY-b.minY+1,b.maxZ-b.minZ+1],includesAir:!!o.includeAir,gameVersion:String(c.gameVersion||c.version),dataVersion:c.dataVersion,palette:[],blocks:[],metadata:clone(o.metadata||{})},indices={};
        if(typeof t.dataVersion!=="number")throw new Error("native context does not provide dataVersion");
        for(var x=b.minX;x<=b.maxX;x++)for(var y=b.minY;y<=b.maxY;y++)for(var z=b.minZ;z<=b.maxZ;z++){var s=api.get_block_full(x,y,z);if(!o.includeAir&&isAir(s))continue;var p=canonicalState(s),key=JSON.stringify(p),index=indices[key];if(index===undefined){index=t.palette.length;indices[key]=index;t.palette.push(p);}var cell={pos:[x-b.minX,y-b.minY,z-b.minZ],state:index};if(s.blockEntity!==undefined)cell.blockEntity=s.blockEntity;t.blocks.push(cell);}return validateTemplate(t);
    };
    api.save_template=function(template,name) {name=templateName(name);requireMethod("saveTemplate").call(backend,name,JSON.stringify(validateTemplate(template)));return {name:name,saved:true};};
    api.load_template=function(name) {return validateTemplate(decode(requireMethod("loadTemplate").call(backend,templateName(name))));};
    api.list_templates=function() {return decode(requireMethod("listTemplates").call(backend));};
    function transformPosition(p,size,rotation,mirror) {var x=p[0],y=p[1],z=p[2],w=size[0],d=size[2];if(mirror==="front_back")x=w-1-x;if(mirror==="left_right")z=d-1-z;if(rotation===90)return [d-1-z,y,x];if(rotation===180)return [w-1-x,y,d-1-z];if(rotation===270)return [z,y,w-1-x];return [x,y,z];}
    api.paste_structure=function(template,x,y,z,o) {
        o=options(o);integer(x);integer(y);integer(z);var i,t=validateTemplate(template),rotation=o.rotation===undefined?(o.rotate||0):o.rotation;integer(rotation,"rotation");if(rotation%90!==0)throw new Error("rotation must be a multiple of 90");rotation=((rotation%360)+360)%360;
        var mirror=o.mirror===true?"front_back":(o.mirror||"none");if(["none","front_back","left_right"].indexOf(mirror)<0)throw new Error("invalid mirror");var size=rotation%180===0?t.size.slice():[t.size[2],t.size[1],t.size[0]],b=checkBounds(bounds(x,y,z,x+size[0]-1,y+size[1]-1,z+size[2]-1)),transformed=[],changes=[],before=written,covered={};
        var c=context();if(o.allowVersionMismatch!==true&&t.dataVersion!==c.dataVersion)throw new Error("template dataVersion differs; migrate template or explicitly allowVersionMismatch");
        for(i=0;i<t.palette.length;i++)transformed.push(api.transform_state(t.palette[i],rotation,mirror));
        for(i=0;i<t.blocks.length;i++){var cell=t.blocks[i],s=clone(transformed[cell.state]),p=transformPosition(cell.pos,t.size,rotation,mirror);covered[p.join(",")]=true;if(s.id==="minecraft:structure_void")continue;if(isAir(s)&&o.includeAir===false)continue;if(cell.blockEntity!==undefined){var entityState=clone(t.palette[cell.state]);entityState.blockEntity=cell.blockEntity;s=api.transform_state(entityState,rotation,mirror);}changes.push({x:x+p[0],y:y+p[1],z:z+p[2],state:s});}
        if(o.replace===true)for(var dx=0;dx<size[0];dx++)for(var dy=0;dy<size[1];dy++)for(var dz=0;dz<size[2];dz++)if(!covered[[dx,dy,dz].join(",")])changes.push({x:x+dx,y:y+dy,z:z+dz,state:AIR});
        batch(changes);if(o.updateConnections!==false)api.update_connections(b.minX,b.minY,b.minZ,b.maxX,b.maxY,b.maxZ);var result=summary("paste_structure",before);result.size=size;result.bounds=b;return result;
    };
    api.import_legacy_template=function(legacy,o) {
        o=options(o);legacy=decode(legacy);if(legacy&&legacy.format==="openallay:structure")return validateTemplate(legacy);if(!legacy||!Array.isArray(legacy.blocks))throw new Error("legacy template requires blocks");
        var c=context(),size=legacy.size||legacy.dimensions;if(size&&!Array.isArray(size))size=[size.width||size.x,size.height||size.y,size.depth||size.z];
        var parsed=[],max=[-Infinity,-Infinity,-Infinity],min=[0,0,0],i,j;
        for(i=0;i<legacy.blocks.length;i++){var old=legacy.blocks[i],p=old.pos||old.position||[old.x,old.y,old.z];if(!Array.isArray(p)||p.length!==3)throw new Error("legacy position is invalid");for(j=0;j<3;j++){integer(p[j]);max[j]=Math.max(max[j],p[j]);min[j]=Math.min(min[j],p[j]);}var raw=old.block!==undefined?old.block:(old.name!==undefined?old.name:old.id);if(typeof raw==="number"&&legacy.palette)raw=legacy.palette[raw];if(raw===undefined&&old.state!==undefined&&legacy.palette)raw=legacy.palette[old.state];var s=state(raw,old.properties||old.props);if(old.blockEntity!==undefined)s.blockEntity=old.blockEntity;parsed.push({pos:p,state:s});}
        if(!size)size=[max[0]-min[0]+1,max[1]-min[1]+1,max[2]-min[2]+1];var t={format:"openallay:structure",version:1,size:size,includesAir:!!o.includeAir,gameVersion:String(c.gameVersion||c.version),dataVersion:c.dataVersion,palette:[],blocks:[],metadata:{importedLegacy:true,legacy:clone(legacy.meta||legacy.metadata||{})}},indices={};
        for(i=0;i<parsed.length;i++){var q=parsed[i],ss=canonicalState(q.state),k=JSON.stringify(ss),idx=indices[k];if(idx===undefined){idx=t.palette.length;indices[k]=idx;t.palette.push(ss);}var out={pos:[q.pos[0]-min[0],q.pos[1]-min[1],q.pos[2]-min[2]],state:idx};if(q.state.blockEntity!==undefined)out.blockEntity=q.state.blockEntity;t.blocks.push(out);if(isAir(q.state))t.includesAir=true;}return validateTemplate(t);
    };
    var util={integer:integer,positive:positive,options:options,state:state,summary:summary,count:function(){return written;},rng:rng,seed:settings.seed===undefined?0:settings.seed};
    require("openallay_builder:terrain").install(api,util);
    require("openallay_builder:presets").install(api,util);
    // Internal dispatch quantum, not a user-facing build limit. No JS callback enters Java.
    Object.keys(api).forEach(function(name){
        if((name.indexOf("build_")===0||name.indexOf("place_")===0||name==="flatten_area"||name==="clear_vegetation")&&name!=="place_block"){
            var method=api[name];api[name]=function(){batchDepth++;var result;try{result=method.apply(api,arguments);}catch(error){written-=pending.length;pending=[];throw error;}finally{batchDepth--;}if(batchDepth===0)flush();return result;};
        }
    });
    return api;
};
exports.open=function(o){o=options(o);return exports.create(Java.type("dev.openallay.builder.BuilderRuntime").open(JSON.stringify(o)),o);};
exports.open_world=exports.open;exports.quick_setup=exports.open;
exports.ensure_deps=function(){return {module:"openallay_builder:building",backend:"online",extension:"openallay:builder"};};
exports.resolve_save_path=function(){throw new Error("offline save paths are not used; call open({dimension:...}) for the current online world");};
