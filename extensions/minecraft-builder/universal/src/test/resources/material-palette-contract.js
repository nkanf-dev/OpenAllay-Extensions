/* Detached source contract. Not a native registry or live-game acceptance test.
 * Host entrypoint: exports.run(presetsModule, parsedMaterialPaletteInputs).
 * The second argument is the explicit universal/material-palette-inputs.json
 * source evidence. This fixture never reads files or supplies a runtime fallback.
 */
exports.run = function (presets, paletteInputs) {
    "use strict";
    var own = Object.prototype.hasOwnProperty, assertions = 0;
    function copy(value) { return JSON.parse(JSON.stringify(value)); }
    function check(condition, message) {
        assertions++;
        if (!condition) { throw new Error("material-palette contract: " + message); }
    }
    function same(actual, expected, message) {
        check(JSON.stringify(actual) === JSON.stringify(expected), message);
    }
    function fixture(palette) {
        var changes = [], transforms = [], linked = [], effects = 0, cells = Object.create(null);
        var schemas = Object.create(null), role, entry, key;
        function schema(id, properties) {
            if (!own.call(schemas, id)) { schemas[id] = Object.create(null); }
            for (key in properties) {
                if (own.call(properties,key)) {
                    if (!own.call(schemas[id],key)) { schemas[id][key] = []; }
                    if (schemas[id][key].indexOf(properties[key]) < 0) { schemas[id][key].push(properties[key]); }
                }
            }
        }
        for (role in paletteInputs) {
            if (own.call(paletteInputs,role)) { entry=paletteInputs[role]; schema(entry.id,entry.properties || {}); }
        }
        schema("fixture:panel",{});
        schema("fixture:dry_slab",{type:"bottom"});
        schema("fixture:wet_slab",{type:"bottom",waterlogged:"true",marker:"kept"});
        schema("fixture:dry_stairs",{facing:"east",half:"bottom",shape:"straight"});
        schema("fixture:dry_stairs",{facing:"west"});
        schema("fixture:log",{axis:"x"}); schema("fixture:log",{axis:"y"}); schema("fixture:log",{axis:"z"});
        schema("fixture:bare_log",{});
        schema("fixture:gate",{facing:"north",open:"true",powered:"true",in_wall:"true"});
        schema("fixture:crop",{age:"2",marker:"kept"});
        function doorSchema(id) {
            schema(id,{facing:"north",half:"lower",hinge:"left",open:"false",powered:"false"});
            schema(id,{half:"upper"});
        }
        doorSchema("minecraft:oak_door"); doorSchema("minecraft:birch_door"); doorSchema("fixture:door");
        schema("minecraft:red_bed",{facing:"north",part:"foot",occupied:"false"});
        schema("minecraft:red_bed",{part:"head"});
        schema("minecraft:blue_bed",{facing:"north",part:"foot",occupied:"false"});
        schema("minecraft:blue_bed",{part:"head"});
        function validate(state) {
            if (!own.call(schemas,state.id)) { throw new Error("native_fixture_unknown_id: " + state.id); }
            for (var property in state.properties) {
                if (own.call(state.properties,property) && (!own.call(schemas[state.id],property) || schemas[state.id][property].indexOf(state.properties[property]) < 0)) {
                    throw new Error("native_fixture_invalid_property: " + state.id + " " + property + "=" + state.properties[property]);
                }
            }
        }
        function integer(value, name) {
            if (typeof value !== "number" || !isFinite(value) || Math.floor(value)!==value || Math.abs(value)>2147483647) { throw new Error("invalid integer: " + name); }
            return value;
        }
        function state(block, properties) {
            // Deliberately retain an alias in this test util: the preset must
            // qualify the caller's exact ID before the util can apply it.
            var result = typeof block === "string" ? {id:block} : copy(block), property;
            if (result.id.indexOf(":") < 0) { result.id = "minecraft:" + (result.id === "cobble" ? "cobblestone" : result.id); }
            result.properties = result.properties || {};
            properties = properties || {};
            for (property in properties) { if (own.call(properties,property)) { result.properties[property]=String(properties[property]); } }
            return result;
        }
        function put(x,y,z,block) {
            effects++; changes.push({x:x,y:y,z:z,state:copy(block)});
            cells[x+","+y+","+z] = copy(block);
        }
        var api = {
            context: function () { return {minY:-64,maxY:320,materialPalette:palette}; },
            transform_state: function (block, angle, mirror) {
                transforms.push({state:copy(block),angle:angle,mirror:mirror});
                validate(block);
                var transformed=copy(block), facing=["north","east","south","west"], old=transformed.properties.facing;
                if (facing.indexOf(old)>=0) { transformed.properties.facing=facing[(facing.indexOf(old)+angle/90)%4]; }
                if ((angle===90 || angle===270) && (transformed.properties.axis==="x" || transformed.properties.axis==="z")) {
                    transformed.properties.axis=transformed.properties.axis==="x" ? "z" : "x";
                }
                return transformed;
            },
            flatten_area: function () { effects++; },
            place_door: function (x,y,z,options) { effects++; linked.push({kind:"door",x:x,y:y,z:z,options:copy(options)}); },
            place_bed: function (x,y,z,options) { effects++; linked.push({kind:"bed",x:x,y:y,z:z,options:copy(options)}); },
            update_connections: function () { effects++; }
        };
        var util = {
            integer:integer,
            options:function (options) {
                if (options===undefined || options===null) { return {}; }
                if (typeof options!=="object" || Array.isArray(options)) { throw new Error("options must be an object"); }
                return options;
            },
            state:state, put:put,
            count:function () { return changes.length; },
            summary:function (operation,before) { return {operation:operation,writes:changes.length-before}; }
        };
        presets.install(api,util);
        return {api:api,changes:changes,transforms:transforms,linked:linked,cells:cells,
            effects:function () { return effects; }};
    }
    var cases = [
        {method:"build_simple_house",options:{}},
        {method:"build_skyscraper",options:{width:5,depth:5,floors:1,floorHeight:3}},
        {method:"build_cottage",options:{width:5,depth:5,height:3,facing:"north"}},
        {method:"build_windmill",options:{height:5,radius:2}},
        {method:"build_farm",options:{width:1,depth:1}},
        {method:"build_dock",options:{length:2,width:1,pilingDepth:1}}
    ];
    function invoke(f, preset, options) { return f.api[preset](0,64,0,options); }
    function reject(palette, method, options, expectedMessage) {
        var f=fixture(palette), error;
        try { invoke(f,method,options); } catch (caught) { error=String(caught); }
        check(error!==undefined && error.indexOf(expectedMessage)>=0,"expected preflight error " + expectedMessage);
        check(f.effects()===0,"preflight error has no writes, flatten, linked placement or connection effects");
        return f;
    }
    function findTransform(f,id) {
        for (var i=0;i<f.transforms.length;i++) { if (f.transforms[i].state.id===id) { return f.transforms[i].state; } }
        throw new Error("Missing transformed ID: " + id);
    }
    var roleNames=Object.keys(paletteInputs), consumers=Object.create(null), i,j,role,palette,f,result,original;
    check(roleNames.length===47,"explicit contract covers all 47 current default roles");
    original=JSON.stringify(paletteInputs);
    for (i=0;i<cases.length;i++) {
        f=fixture(copy(paletteInputs)); result=invoke(f,cases[i].method,cases[i].options);
        check(result.operation===cases[i].method,"default preset result " + cases[i].method);
        check(f.changes.length>0,"default preset generates writes " + cases[i].method);
        for (j=0;j<f.transforms.length;j++) {
            var transformed=f.transforms[j].state;
            for (role in paletteInputs) {
                if (own.call(paletteInputs,role)) {
                    var expected=copy(paletteInputs[role]); expected.properties=expected.properties || {};
                    if (JSON.stringify(transformed)===JSON.stringify(expected)) { consumers[role]=cases[i]; }
                }
            }
            check(f.transforms[j].angle===0 && f.transforms[j].mirror==="none","native state transformation delegates angle/mirror");
        }
    }
    for (i=0;i<roleNames.length;i++) {
        role=roleNames[i]; check(own.call(consumers,role),"default role is consumed: " + role);
        palette=copy(paletteInputs); delete palette[role];
        reject(palette,consumers[role].method,consumers[role].options,"material_unavailable");
    }
    for (i=0;i<cases.length;i++) {
        reject(undefined,cases[i].method,cases[i].options,"material_unavailable");
        reject({},cases[i].method,cases[i].options,"material_unavailable");
    }
    palette=copy(paletteInputs); palette.lantern_standing="minecraft:lantern";
    reject(palette,"build_dock",{},"must be a canonical BlockSpec object");
    palette=copy(paletteInputs); palette.lantern_standing={id:"lantern",properties:{hanging:"false"}};
    reject(palette,"build_dock",{},"must be a canonical BlockSpec object");
    palette=copy(paletteInputs); palette.oak_planks={id:"fixture:panel",properties:{}};
    f=fixture(palette); invoke(f,"build_simple_house",{});
    same(f.cells["1,64,1"].id,"fixture:panel","default floor comes from supplied role, not a modern fallback");
    same(f.cells["1,65,0"].id,"fixture:panel","default wall comes from supplied role, not a modern fallback");
    reject(copy(paletteInputs),"build_simple_house",{foundation:"cobble"},"native_fixture_unknown_id: minecraft:cobble");
    f=fixture(copy(paletteInputs)); invoke(f,"build_simple_house",{roof:"fixture:dry_slab"});
    same(findTransform(f,"fixture:dry_slab").properties,{type:"bottom"},"string custom slab gets only essential layout, no waterlogged");
    f=fixture(copy(paletteInputs)); invoke(f,"build_simple_house",{roof:{id:"fixture:wet_slab",properties:{type:"bottom",waterlogged:"true",marker:"kept"}}});
    same(findTransform(f,"fixture:wet_slab").properties,{type:"bottom",waterlogged:"true",marker:"kept"},"custom waterlogged and other properties survive exactly");
    reject(copy(paletteInputs),"build_simple_house",{roof:{id:"fixture:dry_slab",properties:{type:"top"}}},"conflicts with layout");
    reject(copy(paletteInputs),"build_simple_house",{log:{id:"fixture:log",properties:{axis:"x"}}},"conflicts with layout");
    reject(copy(paletteInputs),"build_simple_house",{log:"fixture:bare_log"},"native_fixture_invalid_property");
    f=fixture(copy(paletteInputs)); invoke(f,"build_cottage",{facing:"north",log:"fixture:log",roofStair:"fixture:dry_stairs",roofSlab:"fixture:dry_slab"});
    var axes={},roofFacings={};
    for (i=0;i<f.transforms.length;i++) {
        if (f.transforms[i].state.id==="fixture:log") { axes[f.transforms[i].state.properties.axis]=true; }
        if (f.transforms[i].state.id==="fixture:dry_stairs") {
            var properties=f.transforms[i].state.properties; roofFacings[properties.facing]=true;
            check(!own.call(properties,"waterlogged"),"custom stairs have no fabricated waterlogged property");
        }
    }
    check(axes.x && axes.y && axes.z,"custom log string preserves corner and beam axis geometry");
    check(roofFacings.east && roofFacings.west,"custom roof string preserves both roof plane facings");
    reject(copy(paletteInputs),"build_cottage",{roofStair:{id:"fixture:dry_stairs",properties:{facing:"north"}}},"conflicts with layout");
    palette=copy(paletteInputs); delete palette.spruce_planks;
    f=fixture(palette); invoke(f,"build_dock",{length:1,width:1,deck:"fixture:panel"});
    same(f.cells["0,64,0"].id,"fixture:panel","explicit option does not require its absent default role");
    f=fixture(copy(paletteInputs)); invoke(f,"build_farm",{width:1,depth:1,gate:{id:"fixture:gate",properties:{open:"true",powered:"true",in_wall:"true"}}});
    same(findTransform(f,"fixture:gate").properties,{open:"true",powered:"true",in_wall:"true",facing:"north"},"custom gate retains explicit non-layout values");
    reject(copy(paletteInputs),"build_farm",{gate:{id:"fixture:gate",properties:{facing:"west"}}},"conflicts with layout");
    f=fixture(copy(paletteInputs)); invoke(f,"build_farm",{width:1,depth:1,crops:["beetroots"]});
    same(findTransform(f,"minecraft:beetroots").properties,{},"explicit crop ID has native defaults, not inferred modern age");
    f=fixture(copy(paletteInputs)); invoke(f,"build_farm",{width:1,depth:1,crops:[{block:{id:"fixture:crop",properties:{age:"2",marker:"kept"}}}]});
    same(findTransform(f,"fixture:crop").properties,{age:"2",marker:"kept"},"custom crop BlockSpec properties survive");
    reject(copy(paletteInputs),"build_farm",{crops:[{block:"beetroots",age:7}]},"native_fixture_invalid_property");
    reject(copy(paletteInputs),"build_farm",{crops:[{block:{id:"fixture:crop",properties:{age:"2"}},age:3}]},"Conflicting crop age");
    f=fixture(copy(paletteInputs)); invoke(f,"build_simple_house",{doorMaterial:"fixture:door",bedColor:"blue"});
    same(findTransform(f,"fixture:door").properties,{facing:"north",half:"lower",hinge:"left",open:"false",powered:"false"},"custom qualified door receives exact linked-state precheck");
    same(findTransform(f,"minecraft:blue_bed").properties,{facing:"north",part:"foot",occupied:"false"},"chosen bed color receives exact linked-state precheck");
    check(f.linked.length===2,"house linked blocks are placed after successful preflight");
    same(f.linked[0].options.color,"blue","chosen bed color is not substituted");
    same(f.linked[1].options.material,"fixture:door","chosen door material is not substituted");
    reject(copy(paletteInputs),"build_simple_house",{doorMaterial:"fixture:missing_door"},"native_fixture_unknown_id");
    reject(copy(paletteInputs),"build_simple_house",{bedColor:"aqua"},"bedColor must be one of");
    f=fixture(copy(paletteInputs)); invoke(f,"build_cottage",{facing:"east"});
    for (i=0;i<f.transforms.length;i++) { check(f.transforms[i].angle===90,"all cottage states delegate selected whole-build rotation"); }
    same(JSON.stringify(paletteInputs),original,"role source fixture is not mutated");
    return {contract:"material-palette",assertions:assertions,roles:roleNames.length,presets:cases.length};
};
