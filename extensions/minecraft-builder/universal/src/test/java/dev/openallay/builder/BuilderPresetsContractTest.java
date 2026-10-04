package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Semantic landmarks and independent coordinate checks against the shipped Rhino presets. */
final class BuilderPresetsContractTest {
    private static final String OBSERVED_EMPTY_WORLD = String.join("\n",
                "// Only this fixture's chosen world is known empty. Production unknown cells stay unknown.",
                "var priorRead=backend.read;",
                "backend.read=function(x,y,z) {",
                "    var value=priorRead(x,y,z);",
                "    return value==='null' ? JSON.stringify({id:'minecraft:air',properties:{}}) : value;",
                "};",
                "function block(x,y,z) { return cells[key(x,y,z)] || {id:'minecraft:air',properties:{}}; }",
                "function countBlock(id) {",
                "    return Object.keys(cells).filter(function(k) { return cells[k].id==='minecraft:'+id; }).length;",
                "}",
                "");

    private static JsonObject run(String source) {
        return BuilderJsFixture.evaluate(OBSERVED_EMPTY_WORLD+source);
    }

    /** Native registry tests consume this compact union of states emitted by all six real generators. */
    static JsonArray distinctPresetStates() {
        return run(String.join("\n",
                "var uniqueStates={};",
                "function capture(call) {",
                "    reset();call();",
                "    writes.forEach(function(w) {",
                "        var props={};",
                "        Object.keys(w.state.properties||{}).sort().forEach(function(k){props[k]=w.state.properties[k];});",
                "        var state={id:w.state.id,properties:props};",
                "        uniqueStates[JSON.stringify(state)]=state;",
                "    });",
                "}",
                "capture(function(){builder.build_simple_house(0,64,0);});",
                "capture(function(){builder.build_skyscraper(0,64,0);});",
                "capture(function(){builder.build_cottage(0,64,0);});",
                "capture(function(){builder.build_windmill(0,64,0);});",
                "capture(function(){builder.build_farm(0,64,0);});",
                "capture(function(){builder.build_dock(0,64,0);});",
                "['north','east','south','west'].forEach(function(facing) {",
                "    capture(function(){builder.build_simple_house(0,64,0,{w:7,d:7,h:4,facing:facing,",
                "        wall:'birch_planks',log:'birch_log',floor:'gold_block',roof:'birch_slab',",
                "        window:'red_stained_glass',doorMaterial:'birch',bedColor:'blue'});});",
                "    capture(function(){builder.build_cottage(0,64,0,{w:5,d:5,h:3,facing:facing,",
                "        wall:'white_terracotta',roofStair:'spruce_stairs',roofSlab:'spruce_slab',",
                "        log:'spruce_log',window:'blue_stained_glass',doorMaterial:'iron'});});",
                "    capture(function(){builder.build_windmill(0,64,0,{height:5,radius:2,facing:facing,",
                "        bladeFence:'birch_fence',blade:'red_wool',doorMaterial:'spruce'});});",
                "    capture(function(){builder.build_farm(0,64,0,{w:2,d:2,facing:facing,crops:[",
                "        {block:'wheat',age:0},{block:'carrots',age:1},{block:'potatoes',age:2},{block:'beetroots',age:3}],",
                "        fence:'birch_fence',gate:'birch_fence_gate'});});",
                "    capture(function(){builder.build_dock(0,64,0,{length:1,width:1,facing:facing,",
                "        deck:'birch_planks',log:'oak_log',rail:'oak_fence'});});",
                "});",
                "return {states:Object.keys(uniqueStates).sort().map(function(k){return uniqueStates[k];})};",
                "")).getAsJsonArray("states");
    }

    private static void id(JsonObject result,String name,String expected) {
        assertEquals("minecraft:"+expected,result.getAsJsonObject(name).get("id").getAsString(),name);
    }

    private static void property(JsonObject result,String name,String key,String expected) {
        assertEquals(expected,result.getAsJsonObject(name).getAsJsonObject("properties").get(key).getAsString(),name+"."+key);
    }

    @Test
    void defaultHouseHasEveryFurnitureLandmarkWithoutOverwritingDoorBedOrSupports() {
        JsonObject result=run(String.join("\n",
                "var summary=builder.build_simple_house(0,64,0);",
                "var pairs=regions.filter(function(r) {",
                "    return r.length===2 && /_(bed|door)$/.test(r[0].state.id) && r[0].state.id===r[1].state.id;",
                "}).map(function(r) { return r.length; });",
                "return {summary:summary,foundation:block(0,62,0),floor:block(1,64,1),corner:block(0,65,0),",
                "  wall:block(1,65,0),roof:block(-1,70,-1),window:block(2,66,0),",
                "  door:block(3,65,0),doorTop:block(3,66,0),bed:block(1,65,5),head:block(1,65,4),",
                "  bedSupport:block(1,64,4),table:block(2,65,2),tableTop:block(2,66,2),",
                "  furnace:block(5,65,5),chest:block(4,65,5),lantern:block(3,69,3),",
                "  stairs:block(3,64,-1),inside:block(3,65,3),pairs:pairs,updates:connections.length,",
                "  written:writes.length};",
                ""));
        id(result,"foundation","cobblestone"); id(result,"floor","oak_planks"); id(result,"corner","oak_log");
        id(result,"wall","oak_planks"); id(result,"roof","stone_brick_slab"); id(result,"window","glass_pane");
        id(result,"door","oak_door"); property(result,"door","half","lower"); property(result,"doorTop","half","upper");
        id(result,"bed","red_bed"); property(result,"bed","part","foot"); property(result,"head","part","head");
        id(result,"bedSupport","oak_planks"); id(result,"table","oak_fence"); id(result,"tableTop","oak_pressure_plate");
        id(result,"furnace","furnace"); id(result,"chest","chest"); id(result,"lantern","lantern");
        property(result,"lantern","hanging","true"); id(result,"stairs","stone_brick_stairs"); id(result,"inside","air");
        assertEquals(2,result.getAsJsonArray("pairs").size());
        result.getAsJsonArray("pairs").forEach(value->assertEquals(2,value.getAsInt()));
        assertEquals(1,result.get("updates").getAsInt());
        assertEquals(result.get("written").getAsInt(),result.getAsJsonObject("summary").get("writes").getAsInt());
    }

    @Test
    void defaultSkyscraperHasTwelveUsableFloorsDeepFootingsGlassCycleAndRoofDetails() {
        JsonObject result=run(String.join("\n",
                "var summary=builder.build_skyscraper(0,64,0);",
                "var floorLights=[];",
                "for(var level=1;level<=12;level++) floorLights.push(block(2,64+level*5,2).id);",
                "return {summary:summary,foundation:block(0,60,0),first:block(1,64,1),alternate:block(2,64,1),",
                "  glass0:block(1,65,0),glass1:block(1,70,0),glass2:block(1,75,0),glass3:block(1,80,0),",
                "  pier:block(0,83,0),ceiling:block(3,124,3),lights:floorLights,door:block(7,65,0),",
                "  canopy:block(7,67,-2),steps:block(7,64,-1),parapet:block(0,125,5),",
                "  antenna:block(7,128,7),rod:block(7,129,7),ladder:block(1,124,13),",
                "  ladderBacking:block(1,125,14),updates:connections.length};",
                ""));
        assertEquals(12,result.getAsJsonObject("summary").get("floors").getAsInt());
        assertEquals(15,result.getAsJsonObject("summary").get("width").getAsInt());
        id(result,"foundation","stone_bricks"); id(result,"first","smooth_stone"); id(result,"alternate","polished_andesite");
        id(result,"glass0","light_blue_stained_glass"); id(result,"glass1","cyan_stained_glass");
        id(result,"glass2","blue_stained_glass"); id(result,"glass3","light_blue_stained_glass");
        id(result,"pier","iron_block"); id(result,"ceiling","smooth_stone");
        result.getAsJsonArray("lights").forEach(value->assertEquals("minecraft:sea_lantern",value.getAsString()));
        id(result,"door","oak_door"); id(result,"canopy","smooth_stone_slab"); id(result,"steps","stone_brick_stairs");
        id(result,"parapet","stone_brick_wall"); id(result,"antenna","iron_bars"); id(result,"rod","lightning_rod");
        id(result,"ladder","ladder"); id(result,"ladderBacking","iron_block"); assertEquals(1,result.get("updates").getAsInt());
    }

    @Test
    void defaultCottageFacesSouthAndContainsFramePatternRoofChimneyAndSupportedLantern() {
        JsonObject result=run(String.join("\n",
                "var summary=builder.build_cottage(0,64,0);",
                "return {summary:summary,foundation:block(0,61,0),floor:block(-1,64,-1),alternate:block(-2,64,-1),",
                "  log:block(0,65,0),beam:block(-1,68,0),wall:block(-1,65,0),window:block(-2,66,0),",
                "  door:block(-3,65,0),slope:block(1,69,1),ridge:block(-3,73,1),",
                "  lantern:block(-1,67,-1),support:block(-1,68,-1),chimney:block(-6,75,-5),",
                "  campfire:block(-6,76,-5),updates:connections.length};",
                ""));
        assertEquals("south",result.getAsJsonObject("summary").get("facing").getAsString());
        assertEquals("Cottage",result.getAsJsonObject("summary").get("name").getAsString());
        id(result,"foundation","cobblestone"); id(result,"floor","spruce_planks"); id(result,"alternate","oak_planks");
        id(result,"log","oak_log"); property(result,"beam","axis","x"); id(result,"wall","oak_planks");
        id(result,"window","glass_pane"); property(result,"door","facing","south");
        id(result,"slope","dark_oak_stairs"); property(result,"slope","facing","west"); id(result,"ridge","dark_oak_slab");
        id(result,"lantern","lantern"); id(result,"support","oak_log"); id(result,"chimney","bricks");
        id(result,"campfire","campfire"); property(result,"campfire","lit","true"); assertEquals(1,result.get("updates").getAsInt());
    }

    @Test
    void cottageCoordinatesAndNativeStateRotationAgreeForAllFacings() {
        JsonObject result=run(String.join("\n",
                "var names=['north','east','south','west'];",
                "var outcomes=[];",
                "function p(x,y,z,q) { return q===0?[x,y,z]:q===1?[-z,y,x]:q===2?[-x,y,-z]:[z,y,-x]; }",
                "names.forEach(function(facing,q) {",
                "    reset();",
                "    var summary=builder.build_cottage(0,64,0,{w:5,d:6,h:3,facing:facing,name:'Rotated',",
                "        wall:'gold_block',roofStair:'birch_stairs',roofSlab:'birch_slab',log:'birch_log',",
                "        foundation:'stone',floor:'dirt',alternateFloor:'gravel',window:'blue_stained_glass',",
                "        doorMaterial:'minecraft:birch_door',chimney:'cobblestone'});",
                "    var door=p(2,65,0,q), stair=p(-1,68,-1,q), beam=p(1,67,0,q), window=p(0,66,2,q);",
                "    outcomes.push({summary:summary,door:block.apply(null,door),stair:block.apply(null,stair),",
                "        beam:block.apply(null,beam),window:block.apply(null,window),",
                "        rotations:transforms.every(function(t) { return t.rotation===q*90 && t.mirror==='none'; })});",
                "});",
                "return {outcomes:outcomes};",
                ""));
        JsonArray outcomes=result.getAsJsonArray("outcomes");
        List<String> facings=Arrays.asList("north","east","south","west");
        for(int rotation=0;rotation<4;rotation++) {
            JsonObject item=outcomes.get(rotation).getAsJsonObject();
            assertTrue(item.get("rotations").getAsBoolean());
            id(item,"door","birch_door"); property(item,"door","facing",facings.get(rotation));
            id(item,"stair","birch_stairs"); property(item,"stair","facing",facings.get((rotation+1)%4));
            id(item,"beam","birch_log"); property(item,"beam","axis",rotation%2==0?"x":"z");
            id(item,"window","blue_stained_glass");
            assertEquals("Rotated",item.getAsJsonObject("summary").get("name").getAsString());
        }
    }

    @Test
    void defaultWindmillHasTaperedHollowTowerFourFenceAndWoolBladesAndPlankRoof() {
        JsonObject result=run(String.join("\n",
                "var summary=builder.build_windmill(0,64,0);",
                "var arms=[[1,0],[0,1],[-1,0],[0,-1]];",
                "var blades=arms.map(function(a) {return {fence:block(a[0]*5,78+a[1]*5,-4),",
                "    wool:block(a[0]*5-a[1],78+a[1]*5+a[0],-4)};});",
                "return {summary:summary,foundation:block(0,61,0),lower:block(3,65,0),upper:block(2,79,0),",
                "  lowerInterior:block(0,65,0),upperInterior:block(0,79,0),door:block(0,65,-3),",
                "  roof:block(0,80,0),axle:block(0,78,-4),blades:blades,updates:connections.length};",
                ""));
        assertEquals(15,result.getAsJsonObject("summary").get("height").getAsInt());
        id(result,"foundation","cobblestone"); id(result,"lower","stone_bricks"); id(result,"upper","white_concrete");
        id(result,"lowerInterior","air"); id(result,"upperInterior","air"); id(result,"door","oak_door");
        id(result,"roof","spruce_planks"); id(result,"axle","oak_log");
        result.getAsJsonArray("blades").forEach(value->{id(value.getAsJsonObject(),"fence","oak_fence");id(value.getAsJsonObject(),"wool","white_wool");});
        assertEquals(1,result.get("updates").getAsInt());
    }

    @Test
    void shortestWindmillReducesBladesRatherThanRejectingTheDefaultBladeLength() {
        JsonObject result=run(String.join("\n",
                "var summary=builder.build_windmill(-4,64,7,{height:5,radius:2,facing:'west',",
                "    foundation:'stone',lowerWall:'cobblestone',upperWall:'gold_block',bladeFence:'birch_fence',",
                "    blade:'red_wool',roof:'oak_planks',doorMaterial:'birch'});",
                "return {summary:summary,wool:countBlock('red_wool'),fences:countBlock('birch_fence'),",
                "    door:block(-6,65,7),roof:block(-4,70,7)};",
                ""));
        assertEquals(1,result.getAsJsonObject("summary").get("bladeLength").getAsInt());
        assertEquals(4,result.get("wool").getAsInt()); assertEquals(4,result.get("fences").getAsInt());
        id(result,"door","birch_door"); property(result,"door","facing","west"); id(result,"roof","oak_planks");
    }

    @Test
    void defaultFarmHasMoistHydratedPlotsAgeValidCropsAndAnAccessibleGate() {
        JsonObject result=run(String.join("\n",
                "var summary=builder.build_farm(0,64,0);",
                "var errors=[];",
                "Object.keys(cells).forEach(function(k) {",
                "    var p=k.split(',').map(Number),s=cells[k];",
                "    if(s.id==='minecraft:farmland') {",
                "        if(s.properties.moisture!=='7') errors.push('moisture');",
                "        var wet=false;",
                "        for(var dx=-4;dx<=4;dx++) for(var dz=-4;dz<=4;dz++)",
                "            if(block(p[0]+dx,p[1],p[2]+dz).id==='minecraft:water') wet=true;",
                "        if(!wet) errors.push('unhydrated:'+k);",
                "    }",
                "    if(s.id==='minecraft:beetroots' && s.properties.age!=='3') errors.push('beetroot-age');",
                "    if(['minecraft:wheat','minecraft:carrots','minecraft:potatoes'].indexOf(s.id)>=0 && s.properties.age!=='7') errors.push('crop-age');",
                "});",
                "return {summary:summary,plots:countBlock('farmland'),water:countBlock('water'),beets:countBlock('beetroots'),",
                "  fence:block(-1,65,0),gate:block(9,65,-1),gateSupport:block(9,64,-1),",
                "  canal:block(8,64,0),errors:errors,updates:connections.length};",
                ""));
        assertEquals(20,result.getAsJsonObject("summary").get("width").getAsInt());
        assertEquals(16,result.getAsJsonObject("summary").get("depth").getAsInt());
        assertEquals(18*16,result.get("plots").getAsInt());
        assertEquals(18*16,result.getAsJsonObject("summary").get("planted").getAsInt());
        assertTrue(result.get("beets").getAsInt()>0); assertTrue(result.get("water").getAsInt()>0);
        id(result,"fence","oak_fence"); id(result,"gate","oak_fence_gate"); id(result,"gateSupport","dirt");
        id(result,"canal","water"); assertEquals(0,result.getAsJsonArray("errors").size()); assertEquals(1,result.get("updates").getAsInt());
    }

    @Test
    void narrowFarmsAlwaysPlantAndCustomCropAgesArePreserved() {
        JsonObject result=run(String.join("\n",
                "var outcomes=[];",
                "[[1,1],[1,10],[2,1],[8,2],[9,1],[17,1],[35,1]].forEach(function(dim) {",
                "    reset();",
                "    var summary=builder.build_farm(0,64,0,{width:dim[0],depth:dim[1],crops:['beetroots'],",
                "        fence:'birch_fence',gate:'birch_fence_gate',foundation:'stone'});",
                "    var gateKey=Object.keys(cells).filter(function(k){return cells[k].id==='minecraft:birch_fence_gate';})[0];",
                "    var gateX=Number(gateKey.split(',')[0]);",
                "    outcomes.push({summary:summary,crops:countBlock('beetroots'),gateLeadsTo:block(gateX,64,0).id,",
                "        invalid:Object.keys(cells).some(function(k) {return cells[k].id==='minecraft:beetroots'&&Object.keys(cells[k].properties).length!==0;})});",
                "});",
                "reset();",
                "builder.build_farm(0,64,0,{w:1,d:1,crops:[{block:'example:rice',age:2}]});",
                "return {outcomes:outcomes,custom:block(0,65,0)};",
                ""));
        for(JsonElement item:result.getAsJsonArray("outcomes")) {
            JsonObject outcome=item.getAsJsonObject();
            assertTrue(outcome.get("crops").getAsInt()>0); assertFalse(outcome.get("invalid").getAsBoolean());
            assertEquals("minecraft:farmland",outcome.get("gateLeadsTo").getAsString());
            assertEquals(outcome.get("crops").getAsInt(),outcome.getAsJsonObject("summary").get("planted").getAsInt());
        }
        assertEquals("example:rice",result.getAsJsonObject("custom").get("id").getAsString());
        property(result,"custom","age","2");
    }

    @Test
    void defaultDockAndEveryNarrowOrEvenWidthHaveExactDeckWidthPilingsRailsAndEndLanterns() {
        JsonObject result=run(String.join("\n",
                "var summary=builder.build_dock(0,64,0);",
                "var defaults={summary:summary,deck:countBlock('spruce_planks'),piling:block(0,60,0),",
                "    rail:block(0,65,8),lamp:block(4,67,17),endPiling:block(4,60,17)};",
                "var widths=[];",
                "for(var width=1;width<=6;width++) {",
                "    reset();",
                "    builder.build_dock(0,64,0,{length:3,width:width,deck:'birch_planks',log:'oak_log',",
                "        rail:'oak_fence',pilingDepth:2,pilingSpacing:2});",
                "    var xs={};",
                "    Object.keys(cells).forEach(function(k) {if(cells[k].id==='minecraft:birch_planks')xs[k.split(',')[0]]=true;});",
                "    widths.push({width:width,deck:countBlock('birch_planks'),xs:Object.keys(xs).map(Number).sort(),lamps:countBlock('lantern')});",
                "}",
                "return {defaults:defaults,widths:widths};",
                ""));
        JsonObject defaults=result.getAsJsonObject("defaults");
        assertEquals(18,defaults.getAsJsonObject("summary").get("length").getAsInt());
        assertEquals(18*5,defaults.get("deck").getAsInt()); id(defaults,"piling","spruce_log");
        id(defaults,"rail","spruce_fence"); id(defaults,"lamp","lantern"); id(defaults,"endPiling","spruce_log");
        for(JsonElement element:result.getAsJsonArray("widths")) {
            JsonObject outcome=element.getAsJsonObject(); int width=outcome.get("width").getAsInt();
            assertEquals(width*3,outcome.get("deck").getAsInt()); assertEquals(width,outcome.getAsJsonArray("xs").size());
            for(int i=0;i<width;i++) assertEquals(i,outcome.getAsJsonArray("xs").get(i).getAsInt());
            assertEquals(2,outcome.get("lamps").getAsInt());
        }
    }

    @Test
    void invalidDimensionsAliasesOptionsCropsAndWorldHeightFailBeforeAnyWrite() {
        JsonObject result=run(String.join("\n",
                "// Detached registry rejection double; real property domains remain native acceptance work.",
                "var originalTransform=backend.transformState;",
                "backend.transformState=function(json,rotation,mirror){",
                "    var state=JSON.parse(String(json));",
                "    if(state.id==='example:rice')throw new Error('fixture unknown block');",
                "    if(state.id==='minecraft:beetroots'&&state.properties.age!==undefined&&Number(state.properties.age)>3)",
                "        throw new Error('fixture invalid age');",
                "    return originalTransform(json,rotation,mirror);",
                "};",
                "var cases=[",
                "    function(){builder.build_simple_house(0,64,0,{w:6});},",
                "    function(){builder.build_simple_house(0,64,0,{bedColor:'invisible'});},",
                "    function(){builder.build_simple_house(0,64,0,{w:7,width:8});},",
                "    function(){builder.build_cottage(0,64,0,{facing:'up'});},",
                "    function(){builder.build_cottage(0,64,0,{roof_stair:'oak_stairs',roofStair:'birch_stairs'});},",
                "    function(){builder.build_skyscraper(0,64,0,{glass:[]});},",
                "    function(){builder.build_skyscraper(0,64,0,{floor_h:2});},",
                "    function(){builder.build_windmill(0,64,0,{height:4});},",
                "    function(){builder.build_windmill(0,64,0,{height:5,bladeLength:2});},",
                "    function(){builder.build_farm(0,64,0,{crops:[]});},",
                "    function(){builder.build_farm(0,64,0,{crops:[{block:'beetroots',age:4}]});},",
                "    function(){builder.build_farm(0,64,0,{crops:['example:rice']});},",
                "    function(){builder.build_dock(0,64,0,{width:0});},",
                "    function(){builder.build_dock(0,64,0,{width:1.5});},",
                "    function(){builder.build_dock(0,64,0,{colour:'red'});},",
                "    function(){builder.build_simple_house(0,319,0);},",
                "    function(){builder.build_skyscraper(0,319,0);},",
                "    function(){builder.build_cottage(0,319,0);},",
                "    function(){builder.build_windmill(0,319,0);},",
                "    function(){builder.build_farm(0,-64,0);},",
                "    function(){builder.build_dock(2147483647,64,0,{width:2});}",
                "];",
                "var outcomes=cases.map(function(call) {",
                "    reset();var rejected=false;try{call();}catch(error){rejected=true;}",
                "    return {rejected:rejected,writes:writes.length};",
                "});",
                "return {outcomes:outcomes};",
                ""));
        for(JsonElement item:result.getAsJsonArray("outcomes")) {
            assertTrue(item.getAsJsonObject().get("rejected").getAsBoolean());
            assertEquals(0,item.getAsJsonObject().get("writes").getAsInt());
        }
    }

    @Test
    void customHouseAndMinimumSkyscraperKeepFurnitureAndLightsAndUpdateAfterLinkedBlocks() {
        JsonObject result=run(String.join("\n",
                "var originalUpdate=backend.updateConnections;",
                "var completed=[];",
                "backend.updateConnections=function(json) {",
                "    var bed=Object.keys(cells).filter(function(k){return cells[k].id==='minecraft:blue_bed';});",
                "    var doors=Object.keys(cells).filter(function(k){return /_door$/.test(cells[k].id);});",
                "    completed.push({beds:bed.length,doors:doors.length});",
                "    return originalUpdate(json);",
                "};",
                "var house=builder.build_simple_house(0,64,0,{width:8,depth:8,height:4,facing:'east',",
                "    foundation:'stone',wall:'birch_planks',log:'birch_log',floor:'gold_block',",
                "    roof:'birch_slab',window:'red_stained_glass',doorMaterial:'birch',bedColor:'blue'});",
                "var houseResult={summary:house,bed:block(-6,65,1),head:block(-5,65,1),",
                "    door:block(0,65,3),floor:block(-1,64,1),roof:block(1,69,-1),window:block(0,66,2)};",
                "reset();",
                "var tower=builder.build_skyscraper(0,64,0,{width:5,depth:5,floors:1,floorHeight:3,",
                "    foundationDepth:1,foundation:'stone',floor:'dirt',alternateFloor:'gold_block',",
                "    glass:['red_stained_glass'],pier:'diamond_block',ceiling:'iron_block',",
                "    roofWall:'cobblestone_wall',antenna:'oak_fence',doorMaterial:'iron'});",
                "return {house:houseResult,tower:{summary:tower,glass:block(1,65,0),pier:block(0,65,0),",
                "    floor:block(1,64,1),alternate:block(2,64,1),ceiling:block(3,67,3),light:block(2,67,2),",
                "    parapet:block(0,68,1),antenna:block(2,71,2),door:block(2,65,0)},completed:completed};",
                ""));
        JsonObject house=result.getAsJsonObject("house"),tower=result.getAsJsonObject("tower");
        id(house,"bed","blue_bed"); property(house,"bed","part","foot"); property(house,"head","part","head");
        property(house,"head","facing","east"); id(house,"door","birch_door"); property(house,"door","facing","east");
        id(house,"floor","gold_block"); id(house,"roof","birch_slab"); id(house,"window","red_stained_glass");
        id(tower,"glass","red_stained_glass"); id(tower,"pier","diamond_block"); id(tower,"floor","dirt");
        id(tower,"alternate","gold_block"); id(tower,"ceiling","iron_block"); id(tower,"light","sea_lantern");
        id(tower,"parapet","cobblestone_wall"); id(tower,"antenna","oak_fence"); id(tower,"door","iron_door");
        JsonArray completed=result.getAsJsonArray("completed"); assertEquals(2,completed.size());
        assertEquals(2,completed.get(0).getAsJsonObject().get("beds").getAsInt());
        completed.forEach(item->assertEquals(2,item.getAsJsonObject().get("doors").getAsInt()));
    }

    @Test
    void customPalettesAreNativeValidatedBeforeTheFirstMutation() {
        JsonObject result=run(String.join("\n",
                "var oldTransform=backend.transformState;",
                "backend.transformState=function(json,rotation,mirror) {",
                "    if(JSON.parse(json).id==='example:missing') throw new Error('Unknown block ID');",
                "    return oldTransform(json,rotation,mirror);",
                "};",
                "var cases=[",
                "    function(){builder.build_simple_house(0,64,0,{wall:'example:missing'});},",
                "    function(){builder.build_skyscraper(0,64,0,{glass:['example:missing']});},",
                "    function(){builder.build_cottage(0,64,0,{chimney:'example:missing'});},",
                "    function(){builder.build_windmill(0,64,0,{blade:'example:missing'});},",
                "    function(){builder.build_farm(0,64,0,{crops:[{block:'example:missing',age:2}]});},",
                "    function(){builder.build_dock(0,64,0,{deck:'example:missing'});}",
                "];",
                "var outcomes=cases.map(function(call){reset();var rejected=false;try{call();}catch(error){rejected=true;}",
                "    return {rejected:rejected,writes:writes.length};});",
                "return {outcomes:outcomes};",
                ""));
        for(JsonElement item:result.getAsJsonArray("outcomes")) {
            assertTrue(item.getAsJsonObject().get("rejected").getAsBoolean());
            assertEquals(0,item.getAsJsonObject().get("writes").getAsInt());
        }
    }
}
