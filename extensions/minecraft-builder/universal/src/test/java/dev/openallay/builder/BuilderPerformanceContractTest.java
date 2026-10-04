package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Scale assertions compare a separate frozen algorithm, never reduced areas or longer timeouts. */
final class BuilderPerformanceContractTest {
    private static final String SCALE_WORLD = String.join("\n",
                "fixtureContext.minY=-64;fixtureContext.maxY=320;",
                "var transport={regions:0,requested:0,returned:0,dispatches:0,scalar:0,scans:0,probes:0,probeColumns:0,writes:0};",
                "var nativeAir={id:'minecraft:air',properties:{}};",
                "var nativeStone={id:'minecraft:stone',properties:{}};",
                "function observed(x,y,z){return cells[key(x,y,z)]||(y<=64?nativeStone:nativeAir);}",
                "backend.read=function(x,y,z){transport.scalar++;return JSON.stringify(observed(x,y,z));};",
                "backend.readRegion=function(json){",
                "    var b=JSON.parse(String(json)),out=[],volume=(b.maxX-b.minX+1)*(b.maxZ-b.minZ+1)*(b.maxY-b.minY+1);",
                "    transport.regions++;transport.requested+=volume;transport.dispatches+=Math.ceil(volume/256);",
                "    for(var y=b.minY;y<=b.maxY;y++)for(var z=b.minZ;z<=b.maxZ;z++)for(var x=b.minX;x<=b.maxX;x++){",
                "        var s=observed(x,y,z);",
                "        if(!s||s.unknown||s.loaded===false||typeof s.id!=='string')throw new Error('unobserved');",
                "        if(b.omitAir&&s.id==='minecraft:air'&&Object.keys(s.properties||{}).length===0&&s.blockEntity===undefined)continue;",
                "        out.push({x:x,y:y,z:z,state:s});",
                "    }",
                "    transport.returned+=out.length;return JSON.stringify(out);",
                "};",
                "backend.scanColumns=function(json){",
                "    transport.scans++;var b=JSON.parse(String(json)),out=[];",
                "    for(var x=b.minX;x<=b.maxX;x++)for(var z=b.minZ;z<=b.maxZ;z++)out.push({x:x,z:z,y:64,block:'minecraft:stone',properties:{}});",
                "    return JSON.stringify(out);",
                "};",
                "backend.probeColumns=function(json){",
                "    transport.probes++;var r=JSON.parse(String(json)),out=[],x,z,y,col,values,s;",
                "    for(x=r.minX;x<=r.maxX;x++)for(z=r.minZ;z<=r.maxZ;z++){",
                "        transport.probeColumns++;col={x:x,z:z,y:r.fixedY===undefined?64:r.fixedY,block:'minecraft:stone',properties:{}};values=[];",
                "        if(col.y+r.clearance<r.worldMaxY)for(y=col.y;y<=col.y+r.clearance;y++){",
                "            s=observed(x,y,z);values.push({x:x,y:y,z:z,state:s});",
                "        }",
                "        out.push({x:x,z:z,column:col,cells:values});",
                "    }",
                "    return JSON.stringify(out);",
                "};",
                "var originalWrite=backend.write;",
                "backend.write=function(x,y,z,json){transport.writes++;return originalWrite(x,y,z,json);};",
                "// Native preflight reads are not JS facade read calls; validate directly.",
                "backend.writeRegion=function(json){",
                "    var changes=JSON.parse(String(json)),i,j,c,current,expected,a,b,keys;",
                "    for(i=0;i<changes.length;i++){",
                "        c=changes[i];if(c.expectedBefore===undefined)continue;",
                "        current=observed(c.x,c.y,c.z);expected=c.expectedBefore;",
                "        a=current.properties||{};b=expected.properties||{};keys=Object.keys(a);",
                "        if(current.id!==expected.id||current.blockEntity!==expected.blockEntity||keys.length!==Object.keys(b).length)throw new Error('concurrent_edit');",
                "        for(j=0;j<keys.length;j++)if(String(a[keys[j]])!==String(b[keys[j]]))throw new Error('concurrent_edit');",
                "    }",
                "    regions.push(copy(changes));",
                "    for(i=0;i<changes.length;i++){c=changes[i];backend.write(c.x,c.y,c.z,JSON.stringify(c.state));}",
                "    return JSON.stringify({writes:changes.length});",
                "};",
                "");

    private static void sameOutput(JsonObject baseline,JsonObject optimized) {
        assertEquals(baseline.get("summary"),optimized.get("summary"));
        assertEquals(baseline.get("writes"),optimized.get("writes"),"Exact ordered state writes, including block entities");
        assertEquals(baseline.get("cells"),optimized.get("cells"),"Same final world");
    }

    @Test void fullHeight100By100FlattenPreservesExactOrderedOutputAndEliminatesAirTransport() {
        String source=SCALE_WORLD+String.join("\n",
                "seed(3,100,4,{id:'minecraft:chest',properties:{facing:'west'},blockEntity:'{Items:[],Lock:\"keep\"}'});",
                "seed(20,319,30,{id:'minecraft:bricks',properties:{}});",
                "seed(50,150,60,{id:'custom:air',properties:{variant:'solid'}});",
                "seed(1,62,1,{id:'minecraft:cave_air',properties:{}});",
                "seed(2,63,2,{id:'minecraft:void_air',properties:{}});",
                "seed(4,66,4,{id:'minecraft:cave_air',properties:{}});",
                "seed(5,67,5,{id:'minecraft:void_air',properties:{}});",
                "var summary=builder.flatten_area(0,0,99,99,64,{depth:3,clearAbove:true,surface:'stone_bricks'});",
                "return {summary:summary,writes:writes,cells:cells,transport:transport};",
                "");
        JsonObject baseline=BuilderJsFixture.evaluatePerformanceBaseline(source);
        JsonObject optimized=BuilderJsFixture.evaluateUnrestricted(source);
        sameOutput(baseline,optimized);
        JsonObject old=baseline.getAsJsonObject("transport"),now=optimized.getAsJsonObject("transport");
        assertEquals(10000,old.get("regions").getAsInt());
        assertEquals(100,now.get("regions").getAsInt());
        assertEquals(2_590_000,now.get("requested").getAsInt(),"No area or full-height shrink");
        assertEquals(old.get("requested"),now.get("requested"));
        assertEquals(2_590_000,old.get("returned").getAsInt());
        assertEquals(40005,now.get("returned").getAsInt());
        assertEquals(20000,old.get("dispatches").getAsInt());
        assertEquals(10200,now.get("dispatches").getAsInt());
        assertEquals(0,now.get("scalar").getAsInt());
        assertEquals(40003,optimized.getAsJsonArray("writes").size());
    }

    @Test void fullHeight100By100VegetationClearRetainsStructuresLiquidsAndSparseAirIdentity() {
        String source=SCALE_WORLD+String.join("\n",
                "seed(0,70,0,{id:'minecraft:fern',properties:{}});",
                "seed(20,300,30,{id:'minecraft:oak_log',properties:{axis:'y'}});",
                "seed(3,100,4,{id:'minecraft:chest',properties:{facing:'west'},blockEntity:'{Items:[]}'});",
                "seed(50,150,60,{id:'minecraft:water',properties:{level:'0'}});",
                "seed(4,66,4,{id:'minecraft:cave_air',properties:{}});",
                "seed(5,67,5,{id:'minecraft:void_air',properties:{}});",
                "var summary=builder.clear_vegetation(0,65,0,99,319,99);",
                "return {summary:summary,writes:writes,cells:cells,transport:transport};",
                "");
        JsonObject baseline=BuilderJsFixture.evaluatePerformanceBaseline(source);
        JsonObject optimized=BuilderJsFixture.evaluateUnrestricted(source);
        sameOutput(baseline,optimized);
        JsonObject old=baseline.getAsJsonObject("transport"),now=optimized.getAsJsonObject("transport");
        assertEquals(10000,old.get("regions").getAsInt());
        assertEquals(100,now.get("regions").getAsInt());
        assertEquals(2_550_000,now.get("requested").getAsInt());
        assertEquals(old.get("requested"),now.get("requested"));
        assertEquals(6,now.get("returned").getAsInt());
        assertEquals(2,optimized.getAsJsonArray("writes").size());
    }

    @Test void cachedPathFootprintsReadEachSurfaceAndClearanceRangeOnce() {
        String source=SCALE_WORLD+String.join("\n",
                "var summary=builder.build_path({x:0,z:0},{x:99,z:0},{width:5,clearance:2,blocks:['gravel','dirt_path'],seed:12});",
                "return {summary:summary,writes:writes,cells:cells,transport:transport};",
                "");
        JsonObject baseline=BuilderJsFixture.evaluatePerformanceBaseline(source);
        JsonObject optimized=BuilderJsFixture.evaluateUnrestricted(source);
        sameOutput(baseline,optimized);
        assertEquals(2500,baseline.getAsJsonObject("transport").get("regions").getAsInt());
        assertEquals(0,optimized.getAsJsonObject("transport").get("regions").getAsInt());
        assertEquals(0,optimized.getAsJsonObject("transport").get("scans").getAsInt());
        assertEquals(16,optimized.getAsJsonObject("transport").get("probes").getAsInt());
        assertEquals(4096,optimized.getAsJsonObject("transport").get("probeColumns").getAsInt());
        assertEquals(0,optimized.getAsJsonObject("transport").get("scalar").getAsInt());
    }


    @Test void probeTileErrorsOutsideConsumedRouteStayDeferredButDemandedUnknownsStillThrow() {
        JsonObject result=BuilderJsFixture.evaluateUnrestricted(SCALE_WORLD+String.join("\n",
                "var originalProbe=backend.probeColumns;",
                "backend.probeColumns=function(json){",
                "    var r=JSON.parse(String(json)),out=JSON.parse(originalProbe(json));",
                "    out.forEach(function(p){if(p.z!==0){p.column=null;p.cells=[];p.error={code:'chunk_unavailable',message:'off route'};}});",
                "    return JSON.stringify(out);",
                "};",
                "var route=builder.build_path({x:0,z:0},{x:30,z:0});",
                "var prior=writes.length,error='';",
                "try{builder.build_path({x:0,z:1},{x:30,z:1});}catch(e){error=String(e);}",
                "return {route:route,prior:prior,writes:writes.length,error:error};",
                ""));
        assertEquals("built",result.getAsJsonObject("route").get("status").getAsString());
        assertEquals(31,result.get("prior").getAsInt());
        assertEquals(31,result.get("writes").getAsInt());
        assertTrue(result.get("error").getAsString().contains("chunk_unavailable"));
    }

    @Test void headroomErrorsInLaterFootprintDoNotOverrideAnEarlierBlockedCell() {
        String source=SCALE_WORLD+String.join("\n",
                "seed(-1,65,-1,{id:'minecraft:bricks',properties:{}});",
                "var originalProbe=backend.probeColumns;",
                "backend.probeColumns=function(json){",
                "    var out=JSON.parse(originalProbe(json));",
                "    out.forEach(function(p){if(p.x===1&&p.z===1){p.cells=[];p.error={code:'unobserved_block',message:'later footprint'};}});",
                "    return JSON.stringify(out);",
                "};",
                "var summary=builder.build_path({x:0,z:0},{x:3,z:0},{width:3});",
                "return {summary:summary,writes:writes,cells:cells};",
                "");
        sameOutput(BuilderJsFixture.evaluatePerformanceBaseline(source),BuilderJsFixture.evaluateUnrestricted(source));
    }

    @Test void smartSearch100By100KeepsBaselineNoRouteOrderWithOnlyDemandTileProbes() {
        String source=SCALE_WORLD+String.join("\n",
                "for(var z=0;z<100;z++)seed(50,65,z,{id:'minecraft:bricks',properties:{}});",
                "var summary=builder.build_smart_path({x:2,z:2},{x:97,z:97},",
                "    {width:3,bounds:{x1:0,z1:0,x2:99,z2:99}});",
                "return {summary:summary,writes:writes,cells:cells,transport:transport};",
                "");
        JsonObject baseline=BuilderJsFixture.evaluatePerformanceBaseline(source);
        JsonObject optimized=BuilderJsFixture.evaluateUnrestricted(source);
        sameOutput(baseline,optimized);
        assertEquals("no_route",optimized.getAsJsonObject("summary").get("status").getAsString());
        assertEquals(43031,baseline.getAsJsonObject("transport").get("regions").getAsInt());
        assertEquals(5107,baseline.getAsJsonObject("transport").get("scans").getAsInt());
        assertEquals(29,optimized.getAsJsonObject("transport").get("probes").getAsInt());
        assertEquals(0,optimized.getAsJsonObject("transport").get("regions").getAsInt());
        assertEquals(0,optimized.getAsJsonObject("transport").get("scans").getAsInt());
    }

    @Test void perimeterWallsAndPaletteReuseKeepBaselineCommandOrderForFlatAndDegenerateBounds() {
        String source=SCALE_WORLD+String.join("\n",
                "var summary=[];",
                "summary.push(builder.build_walls(0,64,0,99,69,99,'stone_bricks',{corner:'polished_andesite'}));",
                "summary.push(builder.build_walls(110,64,0,110,66,9,'oak_planks',{corner:'oak_log'}));",
                "summary.push(builder.build_walls(120,64,0,129,66,0,'oak_planks',{corner:'oak_log'}));",
                "summary.push(builder.build_walls(140,64,0,140,64,0,'oak_planks',{corner:'oak_log'}));",
                "summary.push(builder.build_pitched_roof(0,80,0,98,99,'oak_stairs','oak_slab'));",
                "return {summary:summary,writes:writes,cells:cells};",
                "");
        sameOutput(BuilderJsFixture.evaluatePerformanceBaseline(source),BuilderJsFixture.evaluateUnrestricted(source));
    }

    @Test void presetPaletteCachingPreservesAllWritesBoundsRotationAndNativeBarriers() {
        String source=SCALE_WORLD+String.join("\n",
                "var summary=[],names=['north','east','south','west'];",
                "names.forEach(function(facing,i){",
                "    summary.push(builder.build_cottage(i*20,64,0,{facing:facing}));",
                "    summary.push(builder.build_simple_house(i*20,64,25,{facing:facing}));",
                "});",
                "return {summary:summary,writes:writes,cells:cells,regions:regions,connections:connections,transforms:transforms.length};",
                "");
        JsonObject baseline=BuilderJsFixture.evaluatePerformanceBaseline(source);
        JsonObject optimized=BuilderJsFixture.evaluateUnrestricted(source);
        sameOutput(baseline,optimized);
        assertEquals(baseline.get("regions"),optimized.get("regions"),"Linked writes retain existing barriers");
        assertEquals(baseline.get("connections"),optimized.get("connections"));
        assertTrue(optimized.get("transforms").getAsInt()<=baseline.get("transforms").getAsInt());
    }

    @Test void custom10000BlockBatchUsesOnePlanAndPreservesUserCallbackResultAndReadBarriers() {
        JsonObject result=BuilderJsFixture.evaluateUnrestricted(SCALE_WORLD+String.join("\n",
                "var originalRegion=backend.writeRegion,regionCalls=0;",
                "backend.writeRegion=function(json){regionCalls++;return originalRegion(json);};",
                "var summary=builder.batch(function(b){",
                "    for(var x=0;x<100;x++)for(var z=0;z<100;z++)b.place_block(x,64,z,'stone_bricks');",
                "});",
                "var firstCalls=regionCalls,firstWrites=writes.length;",
                "var value=builder.batch(function(b){",
                "    b.place_block(0,65,0,'oak_planks');",
                "    var read=b.get_block_full(0,65,0);",
                "    b.batch(function(nested){nested.place_block(1,65,0,'oak_planks');});",
                "    return {read:read.id};",
                "});",
                "return {summary:summary,value:value,firstCalls:firstCalls,firstWrites:firstWrites,calls:regionCalls,writes:writes.length};",
                ""));
        assertEquals(1,result.get("firstCalls").getAsInt());
        assertEquals(10000,result.get("firstWrites").getAsInt());
        assertEquals(10000,result.getAsJsonObject("summary").get("writes").getAsInt());
        assertEquals("minecraft:oak_planks",result.getAsJsonObject("value").get("read").getAsString());
        assertEquals(3,result.get("calls").getAsInt());
        assertEquals(10002,result.get("writes").getAsInt());
    }


    @Test void batchSnapshotsMutableUserStatesBeforeInputIsChanged() {
        JsonObject result=BuilderJsFixture.evaluateUnrestricted(SCALE_WORLD+String.join("\n",
                "var material={id:'minecraft:chest',properties:{facing:'north'},blockEntity:'{Lock:\"first\"}'};",
                "builder.batch(function(b){",
                "    b.place_block(0,65,0,material);",
                "    material.id='minecraft:barrel';material.properties.facing='south';material.blockEntity='{Lock:\"second\"}';",
                "    b.place_block(1,65,0,material);",
                "});",
                "return {first:cells[key(0,65,0)],second:cells[key(1,65,0)]};",
                ""));
        assertEquals("minecraft:chest",result.getAsJsonObject("first").get("id").getAsString());
        assertEquals("north",result.getAsJsonObject("first").getAsJsonObject("properties").get("facing").getAsString());
        assertEquals("{Lock:\"first\"}",result.getAsJsonObject("first").get("blockEntity").getAsString());
        assertEquals("minecraft:barrel",result.getAsJsonObject("second").get("id").getAsString());
    }

    @Test void batchErrorsDropOnlyUnflushedPlansAndLifecycleNeverOvertakesPendingWrites() {
        JsonObject result=BuilderJsFixture.evaluateUnrestricted(SCALE_WORLD+String.join("\n",
                "var error='';",
                "try{builder.batch(function(b){",
                "    b.place_block(0,65,0,'stone');b.get_block_full(0,65,0);",
                "    b.place_block(1,65,0,'stone');throw new Error('stop');",
                "});}catch(e){error=String(e);}",
                "var partial=writes.length;",
                "var after=builder.batch(function(b){b.place_block(2,65,0,'stone');return b.finish();});",
                "return {error:error,partial:partial,writes:writes.length,finished:after.completed};",
                ""));
        assertTrue(result.get("error").getAsString().contains("stop"));
        assertEquals(1,result.get("partial").getAsInt());
        assertEquals(2,result.get("writes").getAsInt());
        assertTrue(result.get("finished").getAsBoolean());
    }

    @Test void sparsePlansRejectUnknownCellsAndRetainExactCaveVoidAndEntityBeforeImages() {
        JsonObject result=BuilderJsFixture.evaluateUnrestricted(String.join("\n",
                "fixtureContext.minY=0;fixtureContext.maxY=4;",
                "for(var x=0;x<2;x++)for(var y=0;y<4;y++)seed(x,y,0,{id:'minecraft:air',properties:{}});",
                "seed(0,0,0,{id:'minecraft:cave_air',properties:{}});",
                "seed(0,1,0,{id:'minecraft:void_air',properties:{}});",
                "seed(0,3,0,{id:'minecraft:chest',properties:{facing:'west'},blockEntity:'{Lock:\"old\"}'});",
                "builder.flatten_area(0,0,0,0,1,{depth:1});",
                "var expected=regions[0].map(function(c){return c.expectedBefore;});",
                "writes.length=0;regions.length=0;seed(1,3,0,null);",
                "var error='';try{builder.flatten_area(0,0,1,0,1,{depth:1});}catch(e){error=String(e);}",
                "return {expected:expected,error:error,writes:writes.length};",
                ""));
        assertEquals("minecraft:cave_air",result.getAsJsonArray("expected").get(0).getAsJsonObject().get("id").getAsString());
        assertEquals("minecraft:void_air",result.getAsJsonArray("expected").get(1).getAsJsonObject().get("id").getAsString());
        assertEquals("{Lock:\"old\"}",result.getAsJsonArray("expected").get(2).getAsJsonObject().get("blockEntity").getAsString());
        assertTrue(result.get("error").getAsString().contains("unobserved"));
        assertEquals(0,result.get("writes").getAsInt());
    }
}
