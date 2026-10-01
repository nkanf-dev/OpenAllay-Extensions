package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Real restricted Rhino module tests, with an independent Java Dijkstra oracle. */
final class BuilderTerrainContractTest {
    private static final String WORLD = """
            fixtureContext.minY=-6; fixtureContext.maxY=9;
            function fill(x1,z1,x2,z2,height) {
                for(var x=x1;x<=x2;x++) for(var z=z1;z<=z2;z++) {
                    var h=typeof height==='function'?height(x,z):height;
                    for(var y=fixtureContext.minY;y<fixtureContext.maxY;y++)
                        seed(x,y,z,{id:y<=h?'minecraft:stone':'minecraft:air',properties:{}});
                }
            }
            function id(x,y,z) { return builder.get_block_full(x,y,z).id; }
            """;

    private static JsonObject run(String code) { return BuilderJsFixture.evaluate(WORLD + code); }

    private static final String SCAN_CASES = """
            fixtureContext.minY=-180;fixtureContext.maxY=513;
            // Unseeded cells in this scan fixture are observed air, unlike the unknown-read tests.
            backend.read=function(x,y,z){var value=cells[key(x,y,z)];return JSON.stringify(value===undefined?{id:'minecraft:air',properties:{}}:value);};
            for(var x=0;x<=12;x++)seed(x,-150,0,{id:'minecraft:stone',properties:{}});
            seed(0,500,0,{id:'minecraft:chest',properties:{facing:'west'},blockEntity:'{Items:[]}'});
            seed(1,480,0,{id:'minecraft:water',properties:{level:'0'}});
            seed(2,470,0,{id:'minecraft:oak_leaves',properties:{persistent:'false'}});
            seed(3,460,0,{id:'minecraft:oak_leaves',properties:{persistent:'true'}});
            seed(4,450,0,{id:'minecraft:oak_log',properties:{axis:'y'}});
            seed(5,440,0,{id:'minecraft:stripped_oak_log',properties:{axis:'x'}});
            seed(6,430,0,{id:'custom:soil',properties:{variant:'damp'}});
            seed(7,420,0,{id:'custom:leaves',properties:{persistent:'false'}});
            seed(8,410,0,{id:'custom:air',properties:{}});
            seed(9,400,0,{id:'minecraft:stone',properties:{waterlogged:'true'}});
            seed(10,390,0,{id:'minecraft:fern',properties:{}});
            seed(11,-150,0,{id:'minecraft:air',properties:{}});
            seed(12,513,0,{id:'custom:soil',properties:{variant:'above-window'}});
            var scanOptions={groundBlocks:['custom:soil','custom:leaves','custom:air','oak_leaves','oak_log','stripped_oak_log','fern','water','air']};
            function scans(api){return [api.scan_terrain(12,0,0,0),api.scan_ground(0,0,12,0,scanOptions),
                api.scan_ground(0,0,12,0,{minY:-160,maxY:430,groundBlocks:scanOptions.groundBlocks})];}
            """;

    private static JsonArray nativeScans(JsonObject fixture,JsonArray requests) {
        JsonObject cells=fixture.getAsJsonObject("cells");
        BuilderSessionTest.Backend backend=new BuilderSessionTest.Backend(java.nio.file.Path.of("unused")) {
            @Override public String read(net.minecraft.core.BlockPos pos) {
                JsonElement value=cells.get(pos.getX()+","+pos.getY()+","+pos.getZ());
                if(value==null)return BuilderSessionTest.AIR;
                if(!value.isJsonObject() || value.getAsJsonObject().has("unknown") || value.getAsJsonObject().has("loaded"))
                    throw new BuilderException("unobserved_block","fixture unknown");
                return value.toString();
            }
        };
        JsonArray results=new JsonArray();
        for(JsonElement request:requests) {
            TerrainScan.Cursor cursor=new TerrainScan.Cursor(TerrainScan.Request.parse(request.toString()));
            JsonArray columns=new JsonArray();
            while(!cursor.done())for(TerrainScan.Column column:cursor.capture(backend,256).columns())columns.add(column.json());
            results.add(columns);
        }
        return results;
    }

    @Test void batchedNativePredicatesExactlyMatchShippedLegacyJsForEntireDimensionCustomGroundAndRanges() {
        JsonObject baseline=BuilderJsFixture.evaluateLegacyTerrain(SCAN_CASES+"return {columns:scans(builder),cells:cells};");
        JsonObject transport=BuilderJsFixture.evaluateBatched(SCAN_CASES+"return {columns:scans(batchedBuilder),requests:scanRequests};",baseline.get("columns"));
        JsonArray actual=nativeScans(baseline,transport.getAsJsonArray("requests"));
        assertEquals(baseline.get("columns"),actual);
        JsonObject replay=BuilderJsFixture.evaluateBatched(SCAN_CASES+"return {columns:scans(batchedBuilder),writes:writes.length};",actual);
        assertEquals(baseline.get("columns"),replay.get("columns"));
        assertEquals(0,replay.get("writes").getAsInt());
        assertEquals("minecraft:chest",actual.get(0).getAsJsonArray().get(0).getAsJsonObject().get("block").getAsString());
        assertTrue(actual.get(1).getAsJsonArray().get(11).getAsJsonObject().get("y").isJsonNull());
    }

    @Test void batchedScanFacadeUsesOneDetachedCommandForAll2401ColumnsAndNeverCallsIndividualReads() {
        JsonArray replies=new JsonArray();
        JsonObject result=BuilderJsFixture.evaluateBatched("""
                var reply=[];
                for(var x=-24;x<=24;x++)for(var z=-24;z<=24;z++)
                    reply.push({x:x,z:z,y:64,block:'minecraft:stone',properties:{}});
                scanReplies.push(reply);
                backend.read=function(){throw new Error('per-cell owner call is forbidden');};
                var columns=batchedBuilder.scan_ground(24,24,-24,-24);
                return {count:columns.length,first:columns[0],last:columns[2400],requests:scanRequests,writes:writes.length};
                """,replies);
        assertEquals(2401,result.get("count").getAsInt());
        assertEquals(1,result.getAsJsonArray("requests").size());
        JsonObject command=result.getAsJsonArray("requests").get(0).getAsJsonObject();
        assertEquals(-24,command.get("minX").getAsInt());assertEquals(-24,command.get("minZ").getAsInt());
        assertEquals(24,command.get("maxX").getAsInt());assertEquals(24,command.get("maxZ").getAsInt());
        assertEquals(-64,command.get("minY").getAsInt());assertEquals(320,command.get("maxY").getAsInt());
        assertEquals(24,result.getAsJsonObject("last").get("x").getAsInt());
        assertEquals(24,result.getAsJsonObject("last").get("z").getAsInt());
        assertEquals(0,result.get("writes").getAsInt());
    }

    @Test void invalidBatchedScanOptionsAndUnknownNativeCellsNeverReturnFabricatedMissingColumns() {
        JsonArray replies=new JsonArray();replies.add(new JsonArray());
        JsonObject invalid=BuilderJsFixture.evaluateBatched("""
                var failures=[];
                [function(){batchedBuilder.scan_ground(0,0,0,0,{minY:-65});},
                 function(){batchedBuilder.scan_ground(0,0,0,0,{maxY:321});},
                 function(){batchedBuilder.scan_ground(0,0,0,0,{groundBlocks:'custom:soil'});}].forEach(function(f){
                    try{f();failures.push(false);}catch(e){failures.push(true);}
                });
                return {failures:failures,requests:scanRequests.length,writes:writes.length};
                """,replies);
        for(JsonElement failure:invalid.getAsJsonArray("failures"))assertTrue(failure.getAsBoolean());
        assertEquals(0,invalid.get("requests").getAsInt());assertEquals(0,invalid.get("writes").getAsInt());
        JsonObject baseline=BuilderJsFixture.evaluateLegacyTerrain(SCAN_CASES+"return {columns:scans(builder),cells:cells};");
        JsonObject transport=BuilderJsFixture.evaluateBatched(SCAN_CASES+"return {requests:scanRequests,columns:scans(batchedBuilder)};",baseline.get("columns"));
        baseline.getAsJsonObject("cells").add("0,512,0",com.google.gson.JsonNull.INSTANCE);
        assertThrows(BuilderException.class,()->nativeScans(baseline,transport.getAsJsonArray("requests")));
    }

    @Test void detachedFixtureBatchSeamsObserveChangedCellsAndPropertiesOnEveryLoopIteration() {
        JsonObject result=BuilderJsFixture.evaluate("""
                fixtureContext.minY=-3;fixtureContext.maxY=4;
                for(var y=-3;y<4;y++)seed(0,y,0,{id:y<=0?'minecraft:stone':'minecraft:air',properties:{}});
                seed(0,2,0,{id:'minecraft:water',properties:{level:'0'}});
                var request={minX:0,minZ:0,maxX:0,maxZ:0,minY:-3,maxY:4,groundOnly:false,ground:['minecraft:stone'],vegetation:[]};
                var terrain=JSON.parse(backend.scanColumns(JSON.stringify(request)));
                request.groundOnly=true;var ground=JSON.parse(backend.scanColumns(JSON.stringify(request)));
                var region=JSON.parse(backend.readRegion(JSON.stringify({minX:0,minZ:0,maxX:0,maxZ:0,minY:-3,maxY:3})));
                return {terrain:terrain,ground:ground,region:region};
                """);
        assertEquals(2,result.getAsJsonArray("terrain").get(0).getAsJsonObject().get("y").getAsInt());
        assertEquals(0,result.getAsJsonArray("ground").get(0).getAsJsonObject().get("y").getAsInt());
        assertEquals("minecraft:stone",result.getAsJsonArray("region").get(3).getAsJsonObject().getAsJsonObject("state").get("id").getAsString());
        assertEquals("minecraft:water",result.getAsJsonArray("region").get(5).getAsJsonObject().getAsJsonObject("state").get("id").getAsString());
    }

    @Test void flattenClearAndPathUseDetachedColumnBatchesWithoutAnyPerVoxelFacadeCalls() {
        JsonObject r=BuilderJsFixture.evaluateUnrestricted("""
                var regionsRead=0,scalarReads=0,scans=0,probes=0;
                backend.read=function(){scalarReads++;throw new Error('per-voxel facade forbidden');};
                backend.readRegion=function(json){
                    regionsRead++;var b=JSON.parse(String(json)),values=[];
                    for(var y=b.minY;y<=b.maxY;y++)for(var z=b.minZ;z<=b.maxZ;z++)for(var x=b.minX;x<=b.maxX;x++)
                        values.push({x:x,y:y,z:z,state:{id:y<=64?'minecraft:stone':'minecraft:air',properties:{}}});
                    return JSON.stringify(values);
                };
                backend.scanColumns=function(json){scans++;var b=JSON.parse(String(json)),columns=[];
                    for(var x=b.minX;x<=b.maxX;x++)for(var z=b.minZ;z<=b.maxZ;z++)columns.push({x:x,z:z,y:64,block:'minecraft:stone',properties:{}});
                    return JSON.stringify(columns);
                };
                backend.probeColumns=function(json){probes++;var r=JSON.parse(String(json)),out=[];
                    for(var x=r.minX;x<=r.maxX;x++)for(var z=r.minZ;z<=r.maxZ;z++){
                        var cells=[];for(var y=64;y<=64+r.clearance;y++)cells.push({x:x,y:y,z:z,state:{id:y===64?'minecraft:stone':'minecraft:air',properties:{}}});
                        out.push({x:x,z:z,column:{x:x,z:z,y:64,block:'minecraft:stone',properties:{}},cells:cells});
                    }return JSON.stringify(out);
                };
                // Native expectedBefore checks are covered at the Java seam; this test counts facade transports.
                backend.writeRegion=function(json){var edits=JSON.parse(String(json));regions.push(edits);return JSON.stringify({verified:edits.length});};
                builder.flatten_area(-24,-24,24,24,64,{depth:0});
                var flattened={regionReads:regionsRead,writePlans:regions.length,assignments:regions[0].length};
                regionsRead=0;regions.length=0;
                builder.clear_vegetation(-24,65,-24,24,319,24);
                var cleared={regionReads:regionsRead,writePlans:regions.length,assignments:regions.length?regions[0].length:0};
                regionsRead=0;regions.length=0;
                var path=builder.build_path({x:-24,z:0},{x:24,z:0});
                return {flattened:flattened,cleared:cleared,path:path,pathReads:regionsRead,pathScans:scans,pathProbes:probes,scalarReads:scalarReads};
                """);
        assertEquals(0,r.get("scalarReads").getAsInt());
        assertEquals(49,r.getAsJsonObject("flattened").get("regionReads").getAsInt());
        assertEquals(1,r.getAsJsonObject("flattened").get("writePlans").getAsInt());
        assertEquals(2401,r.getAsJsonObject("flattened").get("assignments").getAsInt());
        assertEquals(49,r.getAsJsonObject("cleared").get("regionReads").getAsInt());
        assertEquals(0,r.getAsJsonObject("cleared").get("assignments").getAsInt());
        assertEquals("built",r.getAsJsonObject("path").get("status").getAsString());
        assertEquals(0,r.get("pathReads").getAsInt());assertEquals(0,r.get("pathScans").getAsInt());
        assertEquals(4,r.get("pathProbes").getAsInt());
    }

    @Test void derivedVegetationAndPathEditsCarryObservedImagesAndRejectChangeBeforeNativePreflight() {
        JsonObject r=run("""
                fill(0,0,0,0,0);seed(0,1,0,{id:'minecraft:fern',properties:{}});
                var original=backend.writeRegion;
                backend.writeRegion=function(json){seed(0,1,0,{id:'minecraft:chest',properties:{facing:'north'},blockEntity:'{Items:[]}'});return original(json);};
                var error='';try{builder.clear_vegetation(0,1,0,0,1,0);}catch(e){error=String(e);}
                return {error:error,writes:writes.length,current:cells[key(0,1,0)]};
                """);
        assertTrue(r.get("error").getAsString().contains("concurrent_edit"));assertEquals(0,r.get("writes").getAsInt());
        assertEquals("minecraft:chest",r.getAsJsonObject("current").get("id").getAsString());
        assertEquals("{Items:[]}",r.getAsJsonObject("current").get("blockEntity").getAsString());
    }

    @Test
    void scansUseEntireNativeHeightAndSeparateGroundTreesBuildingsLiquidsAndMissingColumns() {
        JsonObject r=run("""
                fixtureContext.minY=-180;fixtureContext.maxY=513;
                fill(0,0,4,0,-150);
                seed(0,430,0,{id:'minecraft:oak_log',properties:{}});
                seed(1,490,0,{id:'minecraft:bricks',properties:{}});
                seed(2,350,0,{id:'minecraft:water',properties:{}});
                seed(3,400,0,{id:'custom:soil',properties:{variant:'damp'}});
                for(var y=-180;y<513;y++)seed(4,y,0,{id:'minecraft:air',properties:{}});
                var terrain=builder.scan_terrain(4,0,0,0);
                var ground=builder.scan_ground(0,0,4,0);
                var custom=builder.scan_ground(3,0,3,0,{groundBlocks:['custom:soil']});
                return {terrain:terrain,ground:ground,custom:custom,
                        bounds:builder.get_terrain_bounds(ground),empty:builder.get_terrain_bounds([]),writes:writes.length};
                """);
        assertEquals(430,r.getAsJsonArray("terrain").get(0).getAsJsonObject().get("y").getAsInt());
        assertEquals(490,r.getAsJsonArray("terrain").get(1).getAsJsonObject().get("y").getAsInt());
        assertEquals(350,r.getAsJsonArray("terrain").get(2).getAsJsonObject().get("y").getAsInt());
        for(int i=0;i<4;i++) assertEquals(-150,r.getAsJsonArray("ground").get(i).getAsJsonObject().get("y").getAsInt());
        assertTrue(r.getAsJsonArray("ground").get(4).getAsJsonObject().get("y").isJsonNull());
        assertEquals(400,r.getAsJsonArray("custom").get(0).getAsJsonObject().get("y").getAsInt());
        assertEquals("damp",r.getAsJsonArray("custom").get(0).getAsJsonObject().getAsJsonObject("properties").get("variant").getAsString());
        assertEquals(4,r.getAsJsonObject("bounds").get("found").getAsInt());
        assertEquals(1,r.getAsJsonObject("bounds").get("missing").getAsInt());
        assertEquals(-150,r.getAsJsonObject("bounds").get("minY").getAsInt());
        assertTrue(r.getAsJsonObject("empty").get("maxY").isJsonNull());
        assertEquals(0,r.get("writes").getAsInt());
    }

    @Test
    void scansTreatUnknownAsErrorAndExplicitHeightRangesAsExclusiveAtTop() {
        JsonObject r=run("""
                fill(0,0,1,0,0);seed(0,7,0,{id:'minecraft:bricks',properties:{}});
                var bounded=builder.scan_terrain(0,0,0,0,{minY:-2,maxY:7});
                var failures=[];
                [null,{unknown:true},{loaded:false}].forEach(function(s){
                    seed(1,8,0,s);
                    try{builder.scan_ground(1,0,1,0);failures.push(false);}catch(e){failures.push(true);}
                });
                return {bounded:bounded,failures:failures};
                """);
        assertEquals(0,r.getAsJsonArray("bounded").get(0).getAsJsonObject().get("y").getAsInt());
        for(JsonElement f:r.getAsJsonArray("failures")) assertTrue(f.getAsBoolean());
    }

    @Test
    void flattenUsesConfiguredDepthAndDestructiveFullOrCountedClearanceAndRetainsBlockEntities() {
        JsonObject r=run("""
                fill(0,0,2,0,0);
                for(var x=0;x<=2;x++) for(var y=3;y<=8;y++)seed(x,y,0,{id:'minecraft:bricks',properties:{}});
                builder.flatten_area(0,0,0,0,2,{depth:3});
                builder.flatten_area(1,0,1,0,2,{depth:0,clearAbove:2,surface:{id:'minecraft:chest',properties:{facing:'north'},blockEntity:'{CustomName:"test"}'}});
                builder.flatten_area(2,0,2,0,2,{depth:1,clearAbove:false,underground:'clay'});
                return {full:id(0,8,0),surface:id(0,2,0),depth:id(0,-1,0),below:id(0,-2,0),
                        count4:id(1,4,0),count5:id(1,5,0),depth0:id(1,1,0),
                        chest:builder.get_block_full(1,2,0),kept:id(2,3,0),clay:id(2,1,0)};
                """);
        assertEquals("minecraft:air",r.get("full").getAsString());
        assertEquals("minecraft:grass_block",r.get("surface").getAsString());
        assertEquals("minecraft:dirt",r.get("depth").getAsString());
        assertEquals("minecraft:stone",r.get("below").getAsString());
        assertEquals("minecraft:air",r.get("count4").getAsString());
        assertEquals("minecraft:bricks",r.get("count5").getAsString());
        assertEquals("minecraft:air",r.get("depth0").getAsString());
        assertEquals("{CustomName:\"test\"}",r.getAsJsonObject("chest").get("blockEntity").getAsString());
        assertEquals("minecraft:bricks",r.get("kept").getAsString());
        assertEquals("minecraft:clay",r.get("clay").getAsString());
    }

    @Test
    void flattenBlendUsesSeededInterpolationAndLeavesOutsideColumnsUntouched() {
        JsonObject r=run("""
                function build(seedValue){
                    reset();fill(-4,-4,4,4,0);
                    builder.flatten_area(0,0,0,0,5,{depth:0,blendRadius:3,seed:seedValue});
                    var heights=[];
                    for(var x=-4;x<=4;x++)for(var z=-4;z<=4;z++)heights.push(builder.scan_terrain(x,z,x,z)[0]);
                    return {writes:copy(writes),heights:heights};
                }
                var a=build('blend-a'),b=build('blend-a'),c=build('blend-b');
                return {same:JSON.stringify(a)===JSON.stringify(b),different:JSON.stringify(a)!==JSON.stringify(c),heights:a.heights};
                """);
        assertTrue(r.get("same").getAsBoolean());
        assertTrue(r.get("different").getAsBoolean());
        for(JsonElement e:r.getAsJsonArray("heights")) {
            JsonObject p=e.getAsJsonObject();
            int distance=Math.max(Math.abs(p.get("x").getAsInt()),Math.abs(p.get("z").getAsInt()));
            double expected=distance>3?0:5*(1-distance/4.0);
            int y=p.get("y").getAsInt();
            assertTrue(y==Math.floor(expected)||y==Math.ceil(expected));
        }
    }

    @Test
    void vegetationModePreservesStructuresLiquidsAndUnknownReadsFailBeforeAnyWrite() {
        JsonObject r=run("""
                fill(0,0,4,0,0);
                seed(0,1,0,{id:'minecraft:oak_log',properties:{}});
                seed(1,1,0,{id:'minecraft:bricks',properties:{}});
                seed(2,1,0,{id:'minecraft:water',properties:{}});
                seed(3,1,0,{id:'minecraft:stripped_oak_log',properties:{}});
                seed(4,1,0,{id:'minecraft:oak_leaves',properties:{persistent:'true'}});
                builder.clear_vegetation(4,2,0,0,1,0);
                var kept=[id(0,1,0),id(1,1,0),id(2,1,0),id(3,1,0),id(4,1,0)];
                builder.clear_vegetation(0,1,0,4,2,0,{mode:'all'});
                var all=[id(1,1,0),id(2,1,0),id(3,1,0)];
                reset();fill(0,0,1,0,0);seed(0,1,0,{id:'minecraft:fern',properties:{}});seed(1,8,0,null);
                var failures=[];
                [function(){builder.flatten_area(0,0,1,0,1);},
                 function(){builder.clear_vegetation(0,1,0,1,8,0);},
                 function(){builder.build_path({x:0,z:0},{x:1,z:0});}].forEach(function(f){
                    try{f();failures.push(false);}catch(e){failures.push(true);}
                });
                return {kept:kept,all:all,failures:failures,count:writes.length};
                """);
        String[] expected={"air","bricks","water","stripped_oak_log","oak_leaves"};
        for(int i=0;i<expected.length;i++) assertEquals("minecraft:"+expected[i],r.getAsJsonArray("kept").get(i).getAsString());
        for(JsonElement e:r.getAsJsonArray("all")) assertEquals("minecraft:air",e.getAsString());
        for(JsonElement e:r.getAsJsonArray("failures")) assertTrue(e.getAsBoolean());
        assertEquals(0,r.get("count").getAsInt());
    }

    @Test
    void straightPathsIncludeBothEndpointsInAllQuadrantsWithExactOddAndEvenFootprints() {
        JsonObject r=run("""
                var cases=[];
                for(var width=1;width<=4;width++)for(var ex=-2;ex<=2;ex++)for(var ez=-2;ez<=2;ez++){
                    reset();fill(-4,-4,5,5,0);
                    var result=builder.build_path({x:0,z:0},{x:ex,z:ez},{width:width});
                    cases.push({width:width,ex:ex,ez:ez,result:result,writes:copy(writes)});
                }
                return {cases:cases};
                """);
        for(JsonElement e:r.getAsJsonArray("cases")) {
            JsonObject sample=e.getAsJsonObject(), result=sample.getAsJsonObject("result");
            assertEquals("built",result.get("status").getAsString());
            JsonArray path=result.getAsJsonArray("path");
            assertEquals(0,path.get(0).getAsJsonObject().get("x").getAsInt());
            assertEquals(0,path.get(0).getAsJsonObject().get("z").getAsInt());
            assertEquals(sample.get("ex"),path.get(path.size()-1).getAsJsonObject().get("x"));
            assertEquals(sample.get("ez"),path.get(path.size()-1).getAsJsonObject().get("z"));
            int width=sample.get("width").getAsInt(), low=-((width-1)/2);
            Set<String> expected=new HashSet<>(),actual=new HashSet<>();
            for(JsonElement p:path) {
                int x=p.getAsJsonObject().get("x").getAsInt(),z=p.getAsJsonObject().get("z").getAsInt();
                for(int dx=low;dx<low+width;dx++) for(int dz=low;dz<low+width;dz++) expected.add((x+dx)+",0,"+(z+dz));
            }
            for(JsonElement w:sample.getAsJsonArray("writes")) {
                JsonObject p=w.getAsJsonObject();
                actual.add(p.get("x").getAsInt()+","+p.get("y").getAsInt()+","+p.get("z").getAsInt());
            }
            assertEquals(expected,actual);
            assertEquals(expected.size(),sample.getAsJsonArray("writes").size(),"No duplicate overlapping footprint writes");
            assertEquals(expected.size(),result.get("columns").getAsInt());
        }
    }

    @Test
    void fixedHeightSupportsBridgesPavedFloorsAndNumericOverloadWithoutGroundScan() {
        JsonObject r=run("""
                fill(-2,-2,4,4,-20);
                seed(0,2,0,{id:'minecraft:stone_bricks',properties:{}});
                var bridge=builder.build_path(-1,-1,2,2,2,{width:2,blocks:[{id:'minecraft:chest',properties:{facing:'west'},blockEntity:'{Lock:"road"}'}]});
                return {result:bridge,writes:writes};
                """);
        assertEquals("built",r.getAsJsonObject("result").get("status").getAsString());
        for(JsonElement e:r.getAsJsonArray("writes")) {
            JsonObject w=e.getAsJsonObject();
            assertEquals(2,w.get("y").getAsInt());
            assertEquals("{Lock:\"road\"}",w.getAsJsonObject("state").get("blockEntity").getAsString());
        }
    }

    @Test
    void pathsProtectFullWidthHeadroomLiquidsAndNoCornerCuttingAndReturnExplicitNoRoute() {
        JsonObject r=run("""
                var out=[];
                function check(place,options){
                    reset();fill(-2,-2,4,4,0);place();
                    var result=builder.build_smart_path({x:0,z:0},{x:2,z:0},options);
                    out.push({result:result,writes:writes.length});
                }
                check(function(){seed(1,1,0,{id:'minecraft:bricks',properties:{}});},{});
                check(function(){seed(1,1,1,{id:'minecraft:bricks',properties:{}});},{width:2});
                check(function(){seed(1,1,0,{id:'minecraft:water',properties:{}});},{});
                check(function(){seed(0,1,0,{id:'minecraft:bricks',properties:{}});},{});
                check(function(){seed(2,1,0,{id:'minecraft:bricks',properties:{}});},{});
                reset();fill(0,0,1,1,0);
                seed(1,1,0,{id:'minecraft:bricks',properties:{}});seed(0,1,1,{id:'minecraft:bricks',properties:{}});
                var corner=builder.build_smart_path({x:0,z:0},{x:1,z:1});
                var cornerWrites=writes.length;
                reset();fill(-2,-2,4,4,0);seed(1,1,0,{id:'minecraft:oak_log',properties:{}});
                var tree=builder.build_smart_path({x:0,z:0},{x:2,z:0});
                return {cases:out,corner:corner,cornerWrites:cornerWrites,tree:tree,cleared:id(1,1,0)};
                """);
        for(JsonElement e:r.getAsJsonArray("cases")) {
            assertEquals("no_route",e.getAsJsonObject().getAsJsonObject("result").get("status").getAsString());
            assertEquals(0,e.getAsJsonObject().get("writes").getAsInt());
            assertEquals(0,e.getAsJsonObject().getAsJsonObject("result").getAsJsonArray("path").size());
        }
        assertEquals("no_route",r.getAsJsonObject("corner").get("status").getAsString());
        assertEquals(0,r.get("cornerWrites").getAsInt());
        assertEquals("built",r.getAsJsonObject("tree").get("status").getAsString());
        assertEquals("minecraft:air",r.get("cleared").getAsString());
    }

    @Test
    void pathsAndBlendsUseDeterministicExplicitAndSessionSeeds() {
        JsonObject r=run("""
                function paved(o){reset();fill(-2,-2,9,3,0);builder.build_path({x:0,z:0},{x:7,z:0},o);return JSON.stringify(writes);}
                var a=paved({width:3,blocks:['stone','gravel','sand'],seed:'same'});
                var b=paved({width:3,blocks:['stone','gravel','sand'],seed:'same'});
                var c=paved({width:3,blocks:['stone','gravel','sand'],seed:'different'});
                var d=paved({width:3,blocks:['stone','gravel','sand']});
                var e=paved({width:3,blocks:['stone','gravel','sand'],seed:'contract-seed'});
                return {same:a===b,different:a!==c,session:d===e};
                """);
        assertTrue(r.get("same").getAsBoolean());
        assertTrue(r.get("different").getAsBoolean());
        assertTrue(r.get("session").getAsBoolean());
    }

    @Test
    void invalidOptionsRejectBeforeWritesAndWorldCeilingCannotBeUsedAsClearance() {
        JsonObject r=run("""
                fill(-2,-2,3,3,0);var failures=[];
                [function(){builder.build_path({x:0,z:0},{x:1,z:1},{width:0});},
                 function(){builder.build_path({x:0,z:0},{x:1,z:1},{blocks:[]});},
                 function(){builder.build_smart_path({x:0,z:0},{x:1,z:1},{heightPenalty:-1});},
                 function(){builder.build_smart_path({x:0,z:0},{x:1,z:1},{maxStep:0.5});},
                 function(){builder.build_path({x:0,z:0},{x:1,z:1},{clearance:-1});},
                 function(){builder.scan_ground(0,0,1,1,{minY:-100});},
                 function(){builder.flatten_area(0,0,1,1,0,{clearAbove:-1});},
                 function(){builder.clear_vegetation(0,0,0,1,1,1,{mode:'unknown'});}
                ].forEach(function(f){try{f();failures.push(false);}catch(e){failures.push(true);}});
                var ceiling=builder.build_path({x:0,z:0},{x:0,z:0},{y:8,clearance:1});
                return {failures:failures,writes:writes.length,ceiling:ceiling};
                """);
        for(JsonElement e:r.getAsJsonArray("failures")) assertTrue(e.getAsBoolean());
        assertEquals(0,r.get("writes").getAsInt());
        assertEquals("no_route",r.getAsJsonObject("ceiling").get("status").getAsString());
    }

    record SearchEntry(int x,int z,double cost) {}

    /** No A* heuristic or production route is used by this oracle. */
    private static double dijkstra(int[][] heights,boolean[][] blocked,int width,int step,double penalty,boolean diagonal) {
        int n=heights.length,low=-((width-1)/2),high=low+width-1;
        boolean[][] legal=new boolean[n][n];
        for(int x=0;x<n;x++) for(int z=0;z<n;z++) {
            if(x+low<0||z+low<0||x+high>=n||z+high>=n) continue;
            int min=Integer.MAX_VALUE,max=Integer.MIN_VALUE;boolean valid=true;
            for(int dx=low;dx<=high;dx++) for(int dz=low;dz<=high;dz++) {
                valid&=!blocked[x+dx][z+dz];
                min=Math.min(min,heights[x+dx][z+dz]);max=Math.max(max,heights[x+dx][z+dz]);
            }
            legal[x][z]=valid&&max-min<=step;
        }
        int sx=-low,sz=-low,ex=n-1-high,ez=n-1-high;
        if(!legal[sx][sz]||!legal[ex][ez]) return Double.POSITIVE_INFINITY;
        double[][] best=new double[n][n];for(double[] row:best)Arrays.fill(row,Double.POSITIVE_INFINITY);
        var queue=new PriorityQueue<SearchEntry>(Comparator.comparingDouble(SearchEntry::cost));
        best[sx][sz]=0;queue.add(new SearchEntry(sx,sz,0));
        while(!queue.isEmpty()) {
            var current=queue.remove();int x=current.x(),z=current.z();
            if(current.cost()!=best[x][z])continue;
            if(x==ex&&z==ez)return current.cost();
            for(int dx=-1;dx<=1;dx++)for(int dz=-1;dz<=1;dz++) {
                if(dx==0&&dz==0||!diagonal&&dx!=0&&dz!=0)continue;
                int nx=x+dx,nz=z+dz;
                if(nx<0||nz<0||nx>=n||nz>=n||!legal[nx][nz])continue;
                if(!slope(heights,x,z,nx,nz,low,high,step))continue;
                if(dx!=0&&dz!=0&&(!legal[nx][z]||!legal[x][nz]||
                    !slope(heights,x,z,nx,z,low,high,step)||!slope(heights,nx,z,nx,nz,low,high,step)||
                    !slope(heights,x,z,x,nz,low,high,step)||!slope(heights,x,nz,nx,nz,low,high,step)))continue;
                double candidate=current.cost()+(dx!=0&&dz!=0?Math.sqrt(2):1)+penalty*Math.abs(heights[x][z]-heights[nx][nz]);
                if(candidate<best[nx][nz]){best[nx][nz]=candidate;queue.add(new SearchEntry(nx,nz,candidate));}
            }
        }
        return Double.POSITIVE_INFINITY;
    }

    private static boolean slope(int[][] h,int x,int z,int nx,int nz,int low,int high,int step) {
        for(int dx=low;dx<=high;dx++)for(int dz=low;dz<=high;dz++)
            if(Math.abs(h[x+dx][z+dz]-h[nx+dx][nz+dz])>step)return false;
        return true;
    }

    @Test
    void astarMatchesIndependentDijkstraAcrossRandomSlopesObstaclesWidthsAndMovementModes() {
        Random random=new Random(934729L);
        JsonArray cases=new JsonArray();double[] expected=new double[96];
        for(int test=0;test<expected.length;test++) {
            int n=6,width=test%3+1,step=test%4,low=-((width-1)/2),high=low+width-1;
            double penalty=(test%5)*0.75;boolean diagonal=test%2==0;
            int[][] height=new int[n][n];boolean[][] blocked=new boolean[n][n];
            JsonArray h=new JsonArray(),b=new JsonArray();
            for(int x=0;x<n;x++) {
                JsonArray hr=new JsonArray(),br=new JsonArray();
                for(int z=0;z<n;z++) {
                    height[x][z]=test%4==0?0:random.nextInt(3);
                    blocked[x][z]=test%6!=0&&random.nextDouble()<0.10;
                    hr.add(height[x][z]);br.add(blocked[x][z]);
                }
                h.add(hr);b.add(br);
            }
            expected[test]=dijkstra(height,blocked,width,step,penalty,diagonal);
            JsonObject spec=new JsonObject();spec.add("h",h);spec.add("b",b);spec.addProperty("width",width);
            spec.addProperty("step",step);spec.addProperty("penalty",penalty);spec.addProperty("diagonal",diagonal);
            spec.addProperty("start",-low);spec.addProperty("end",n-1-high);cases.add(spec);
        }
        JsonObject r=run("var cases="+cases+";"+"""
                var answers=[];
                cases.forEach(function(c){
                    reset();fill(0,0,5,5,function(x,z){return c.h[x][z];});
                    for(var x=0;x<=5;x++)for(var z=0;z<=5;z++)if(c.b[x][z])
                        seed(x,c.h[x][z]+1,z,{id:'minecraft:bricks',properties:{}});
                    var answer=builder.build_smart_path({x:c.start,z:c.start},{x:c.end,z:c.end},
                        {bounds:{x1:0,z1:0,x2:5,z2:5},width:c.width,maxStep:c.step,heightPenalty:c.penalty,diagonal:c.diagonal});
                    answers.push({status:answer.status,cost:answer.cost,writes:writes.length});
                });
                return {answers:answers};
                """);
        int built=0,unreachable=0;
        for(int i=0;i<expected.length;i++) {
            JsonObject actual=r.getAsJsonArray("answers").get(i).getAsJsonObject();
            if(Double.isInfinite(expected[i])) {
                unreachable++;assertEquals("no_route",actual.get("status").getAsString(),"case "+i);
                assertEquals(0,actual.get("writes").getAsInt());
            } else {
                built++;assertEquals("built",actual.get("status").getAsString(),"case "+i);
                assertEquals(expected[i],actual.get("cost").getAsDouble(),1e-9,"case "+i);
            }
        }
        assertTrue(built>=12,"Oracle must cover reachable routes");
        assertTrue(unreachable>=12,"Oracle must cover blocked routes");
    }
}
