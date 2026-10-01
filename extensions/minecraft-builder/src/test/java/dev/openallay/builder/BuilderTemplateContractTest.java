package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BuilderTemplateContractTest {
    private static final String ASYMMETRIC = """
            const template={format:'openallay:structure',size:[3,2,4],includesAir:false,
                gameVersion:'26.2',dataVersion:5000,metadata:{},
                palette:[
                    {id:'minecraft:oak_stairs',properties:{facing:'north',half:'bottom',shape:'straight'}},
                    {id:'minecraft:oak_log',properties:{axis:'x'}},
                    {id:'minecraft:oak_fence',properties:{north:'true',east:'false',south:'false',west:'true'}},
                    {id:'minecraft:chest',properties:{facing:'east'}}],
                blocks:[{pos:[0,0,0],state:0},{pos:[2,1,1],state:1},{pos:[1,0,3],state:2},
                        {pos:[0,1,2],state:3,blockEntity:'{id:"minecraft:chest",Items:[{Slot:0b,count:7,id:"minecraft:stone"}],Long:12L,Ints:[I;1,2,3]}'}]};
            """;

    @Test void nativeRegionCaptureKeepsLegacyTemplateOrderingFullPropertiesAndPerCellSnbt() {
        JsonObject result=BuilderJsFixture.evaluate("""
                for(var x=-1;x<=1;x++)for(var y=4;y<=5;y++)for(var z=7;z<=8;z++)
                    seed(x,y,z,{id:'minecraft:stone',properties:{sample:x+','+y+','+z}});
                seed(0,5,8,{id:'minecraft:chest',properties:{facing:'west'},blockEntity:'{Items:[]}'});
                var baseline=builder.scan_structure(1,5,8,-1,4,7,{includeAir:true});
                var captured=0;
                backend.readRegion=function(json){
                    captured++;var b=JSON.parse(String(json)),values=[];
                    for(var y=b.minY;y<=b.maxY;y++)for(var z=b.minZ;z<=b.maxZ;z++)for(var x=b.minX;x<=b.maxX;x++)
                        values.push({x:x,y:y,z:z,state:copy(cells[key(x,y,z)])});
                    return JSON.stringify(values);
                };
                backend.read=function(){throw new Error('per-block capture round trip forbidden');};
                var batched=builder.scan_structure(1,5,8,-1,4,7,{includeAir:true});
                return {baseline:baseline,batched:batched,captured:captured,writes:writes.length};
                """);
        assertEquals(result.get("baseline"),result.get("batched"));assertEquals(1,result.get("captured").getAsInt());assertEquals(0,result.get("writes").getAsInt());
    }

    @Test
    void scanUsesRelativeCoordinatesReversedBoundsAndDeduplicatesCanonicalPaletteStates() {
        JsonObject actual=BuilderJsFixture.evaluate("""
                seed(-2,7,9,{id:'minecraft:oak_stairs',properties:{facing:'north',half:'bottom'}});
                seed(-1,7,9,{id:'minecraft:oak_stairs',properties:{half:'bottom',facing:'north'}});
                seed(0,7,9,{id:'minecraft:oak_stairs',properties:{half:'top',facing:'north'}});
                const template=builder.scan_structure(0,7,9,-2,7,9,{metadata:{author:'contract'}});
                return {template:template,count:writes.length};
                """);
        JsonObject t=actual.getAsJsonObject("template");
        assertEquals("openallay:structure",t.get("format").getAsString());
        assertArray(t.getAsJsonArray("size"),3,1,1);
        assertFalse(t.get("includesAir").getAsBoolean());
        assertEquals("26.2",t.get("gameVersion").getAsString());
        assertEquals(5000,t.get("dataVersion").getAsInt());
        assertEquals(2,t.getAsJsonArray("palette").size());
        JsonArray blocks=t.getAsJsonArray("blocks");
        assertEquals(3,blocks.size());
        assertArray(blocks.get(0).getAsJsonObject().getAsJsonArray("pos"),0,0,0);
        assertArray(blocks.get(2).getAsJsonObject().getAsJsonArray("pos"),2,0,0);
        assertEquals(blocks.get(0).getAsJsonObject().get("state"),blocks.get(1).getAsJsonObject().get("state"));
        assertNotEquals(blocks.get(0).getAsJsonObject().get("state"),blocks.get(2).getAsJsonObject().get("state"));
        assertEquals(0,actual.get("count").getAsInt());
    }

    @Test
    void scanSaveLoadAndPastePreserveTypedSnbtPerCellInsteadOfInThePalette() {
        JsonObject actual=BuilderJsFixture.evaluate("""
                const first='{id:"minecraft:chest",Items:[{Slot:0b,count:7,id:"minecraft:stone"}],Long:12L,Ints:[I;1,2,3]}';
                const second='{id:"minecraft:chest",Items:[],Bytes:[B;1b,-2b],Float:1.25f,Double:2.5d,Short:8s}';
                seed(0,5,0,{id:'minecraft:chest',properties:{facing:'north'},blockEntity:first});
                seed(1,5,0,{id:'minecraft:chest',properties:{facing:'north'},blockEntity:second});
                const captured=builder.scan_structure(0,5,0,1,5,0);
                builder.save_template(captured,'typed-entities');
                const loaded=builder.load_template('typed-entities');
                const names=builder.list_templates();
                reset();builder.paste_structure(loaded,10,20,30,{updateConnections:false});
                return {captured:captured,loaded:loaded,names:names,first:first,second:second,cells:snapshot(),connections:connections};
                """);
        assertEquals(actual.get("captured"),actual.get("loaded"));
        JsonObject t=actual.getAsJsonObject("loaded");
        assertEquals(1,t.getAsJsonArray("palette").size());
        assertFalse(t.getAsJsonArray("palette").get(0).getAsJsonObject().has("blockEntity"));
        assertEquals(actual.get("first"),t.getAsJsonArray("blocks").get(0).getAsJsonObject().get("blockEntity"));
        assertEquals(actual.get("second"),t.getAsJsonArray("blocks").get(1).getAsJsonObject().get("blockEntity"));
        assertEquals(actual.get("first"),actual.getAsJsonObject("cells").getAsJsonObject("10,20,30").get("blockEntity"));
        assertEquals(actual.get("second"),actual.getAsJsonObject("cells").getAsJsonObject("11,20,30").get("blockEntity"));
        assertEquals("typed-entities",actual.getAsJsonArray("names").get(0).getAsString());
        assertTrue(actual.getAsJsonArray("connections").isEmpty());
    }

    @Test
    void everyQuarterTurnAndBothMirrorAxesTransformAsymmetricPositionsDimensionsAndStates() {
        int[][] positions={{0,0,0},{2,1,1},{1,0,3},{0,1,2}};
        List<String> ids=List.of("minecraft:oak_stairs","minecraft:oak_log","minecraft:oak_fence","minecraft:chest");
        for(int rotation:List.of(0,90,180,270)) for(String mirror:List.of("none","front_back","left_right")) {
            JsonObject actual=BuilderJsFixture.evaluate(ASYMMETRIC+"const result=builder.paste_structure(template,10,20,-30,{rotation:"+rotation+",mirror:'"+mirror+"'});return {cells:snapshot(),result:result,transforms:transforms,connections:connections,regions:regions};");
            JsonObject cells=actual.getAsJsonObject("cells");
            assertEquals(4,cells.size(),rotation+" "+mirror);
            int width=rotation%180==0?3:4,depth=rotation%180==0?4:3;
            assertArray(actual.getAsJsonObject("result").getAsJsonArray("size"),width,2,depth);
            assertEquals(1,actual.getAsJsonArray("regions").size());
            assertEquals(5,actual.getAsJsonArray("transforms").size(),"Palette transforms plus the full block-entity state");
            JsonObject entityTransform=actual.getAsJsonArray("transforms").get(4).getAsJsonObject();
            assertTrue(entityTransform.getAsJsonObject("state").get("blockEntity").getAsString().contains("Slot:0b"));
            assertEquals(rotation,entityTransform.get("rotation").getAsInt());
            assertEquals(mirror,entityTransform.get("mirror").getAsString());
            for(int index=0;index<positions.length;index++) {
                int[] transformed=transform(positions[index],rotation,mirror);
                String key=(10+transformed[0])+","+(20+transformed[1])+","+(-30+transformed[2]);
                assertTrue(cells.has(key),rotation+" "+mirror+" missing "+key);
                JsonObject state=cells.getAsJsonObject(key),props=state.getAsJsonObject("properties");
                assertEquals(ids.get(index),state.get("id").getAsString());
                if(index==0) assertEquals(direction("north",rotation,mirror),props.get("facing").getAsString());
                if(index==1) assertEquals(rotation%180==0?"x":"z",props.get("axis").getAsString());
                if(index==2) {
                    for(var entry:Map.of("north","true","east","false","south","false","west","true").entrySet())
                        assertEquals(entry.getValue(),props.get(direction(entry.getKey(),rotation,mirror)).getAsString());
                }
                if(index==3) {
                    assertEquals(direction("east",rotation,mirror),props.get("facing").getAsString());
                    assertTrue(state.get("blockEntity").getAsString().contains("Slot:0b"));
                }
            }
            JsonObject connection=actual.getAsJsonArray("connections").get(0).getAsJsonObject();
            assertEquals(10,connection.get("minX").getAsInt());
            assertEquals(10+width-1,connection.get("maxX").getAsInt());
            assertEquals(20,connection.get("minY").getAsInt());
            assertEquals(21,connection.get("maxY").getAsInt());
            assertEquals(-30+depth-1,connection.get("maxZ").getAsInt());
        }
    }

    @Test
    void explicitAirMissingCellsAndStructureVoidHaveDifferentPasteSemantics() {
        for(boolean replace:List.of(false,true)) for(boolean includeAir:List.of(false,true)) {
            JsonObject actual=BuilderJsFixture.evaluate("""
                    const template={format:'openallay:structure',size:[4,1,1],includesAir:true,
                        gameVersion:'26.2',dataVersion:5000,palette:[
                            {id:'minecraft:stone',properties:{}},{id:'minecraft:air',properties:{}},
                            {id:'minecraft:structure_void',properties:{}}],blocks:[
                            {pos:[0,0,0],state:0},{pos:[1,0,0],state:1},{pos:[3,0,0],state:2}]};
                    for(let x=0;x<4;x++) seed(x,5,0,{id:'minecraft:dirt',properties:{}});
                    """+"const result=builder.paste_structure(template,0,5,0,{replace:"+replace+",includeAir:"+includeAir+"});return {cells:snapshot(),count:writes.length,result:result};");
            JsonObject cells=actual.getAsJsonObject("cells");
            assertEquals("minecraft:stone",cells.getAsJsonObject("0,5,0").get("id").getAsString());
            assertEquals(includeAir?"minecraft:air":"minecraft:dirt",cells.getAsJsonObject("1,5,0").get("id").getAsString());
            assertEquals(replace?"minecraft:air":"minecraft:dirt",cells.getAsJsonObject("2,5,0").get("id").getAsString());
            assertEquals("minecraft:dirt",cells.getAsJsonObject("3,5,0").get("id").getAsString(),"structure_void always skips");
            assertEquals(1+(replace?1:0)+(includeAir?1:0),actual.get("count").getAsInt());
        }
    }

    @Test
    void scanSkipsOnlyObservedAirUnlessRequestedAndRejectsUnobservedCells() {
        JsonObject actual=BuilderJsFixture.evaluate("""
                ['air','cave_air','void_air','structure_void','stone'].forEach(function(id,x) {
                    seed(x,5,0,{id:'minecraft:'+id,properties:{}});
                });
                const omitted=builder.scan_structure(0,5,0,4,5,0);
                const included=builder.scan_structure(0,5,0,4,5,0,{includeAir:true});
                let rejected=false;
                try {builder.scan_structure(0,5,0,5,5,0,{includeAir:true});} catch(e) {rejected=true;}
                return {omitted:omitted,included:included,rejected:rejected,count:writes.length};
                """);
        assertEquals(2,actual.getAsJsonObject("omitted").getAsJsonArray("blocks").size());
        assertEquals(5,actual.getAsJsonObject("included").getAsJsonArray("blocks").size());
        assertFalse(actual.getAsJsonObject("omitted").get("includesAir").getAsBoolean());
        assertTrue(actual.getAsJsonObject("included").get("includesAir").getAsBoolean());
        assertTrue(actual.get("rejected").getAsBoolean());
        assertEquals(0,actual.get("count").getAsInt());
    }

    @Test
    void corruptSchemasFailBeforeAnyWriteOrNativeStateTransform() {
        JsonObject actual=BuilderJsFixture.evaluate(ASYMMETRIC+"""
                const mutations=[
                    function(t){t.format='foreign:structure';},function(t){t.unknownField=2;},
                    function(t){delete t.format;},function(t){t.size=[1,2];},
                    function(t){t.size[0]=0;},function(t){t.size[0]=1.5;},
                    function(t){delete t.includesAir;},function(t){t.gameVersion='';},
                    function(t){t.dataVersion=-1;},function(t){t.palette={};},
                    function(t){t.blocks={};},function(t){t.blocks[0].pos=[0,0];},
                    function(t){t.blocks[0].pos[0]=-1;},function(t){t.blocks[0].pos[0]=3;},
                    function(t){t.blocks[0].state=99;},function(t){t.blocks[0].state=-1;},
                    function(t){t.blocks[0].state=0.5;},function(t){t.blocks.push(copy(t.blocks[0]));},
                    function(t){t.blocks[0].blockEntity={Items:[]};},
                    function(t){t.palette[0].blockEntity='{Items:[]}';},
                    function(t){t.palette[0].id='Invalid ID';},
                    function(t){t.palette[0].id='minecraft:air';},
                    function(t){t.metadata='not an object';}
                ];
                const rejected=mutations.map(function(mutate) {
                    const candidate=copy(template);mutate(candidate);
                    try {builder.paste_structure(candidate,0,5,0);return false;}catch(e){return true;}
                });
                return {rejected:rejected,count:writes.length,transforms:transforms.length};
                """);
        assertEquals(23,actual.getAsJsonArray("rejected").size());
        for(int i=0;i<actual.getAsJsonArray("rejected").size();i++)
            assertTrue(actual.getAsJsonArray("rejected").get(i).getAsBoolean(),"corruption #"+i);
        assertEquals(0,actual.get("count").getAsInt());
        assertEquals(0,actual.get("transforms").getAsInt());
    }

    @Test
    void malformedStoredJsonNamesAndVersionMismatchAreRejectedWithExplicitVersionOverride() {
        JsonObject actual=BuilderJsFixture.evaluate(ASYMMETRIC+"""
                const names=['','..','.','../escape','a/b','a\\\\b','/absolute','name with spaces','name:colon'];
                const rejected=[];
                names.forEach(function(name) {
                    try {builder.save_template(template,name);rejected.push(false);}catch(e){rejected.push(true);}
                    try {builder.load_template(name);rejected.push(false);}catch(e){rejected.push(true);}
                });
                templates['corrupt']='{broken';
                try {builder.load_template('corrupt');rejected.push(false);}catch(e){rejected.push(true);}
                template.dataVersion=4999;
                try {builder.paste_structure(template,0,5,0);rejected.push(false);}catch(e){rejected.push(true);}
                const before=writes.length;
                builder.paste_structure(template,0,5,0,{allowVersionMismatch:true});
                return {rejected:rejected,before:before,count:writes.length};
                """);
        assertEquals(20,actual.getAsJsonArray("rejected").size());
        for(JsonElement rejected:actual.getAsJsonArray("rejected")) assertTrue(rejected.getAsBoolean());
        assertEquals(0,actual.get("before").getAsInt());
        assertEquals(4,actual.get("count").getAsInt());
    }

    @Test
    void legacyAbsoluteCoordinatesAliasesPropertiesAndTypedEntitiesUpgradeToPortableSchema() {
        JsonObject actual=BuilderJsFixture.evaluate("""
                const legacy={blocks:[
                    {x:-2,y:-1,z:-3,block:'oak_plank'},
                    {position:[-1,-1,-3],name:'minecraft:oak_planks'},
                    {pos:[0,0,-2],id:'modded:chest',props:{facing:'west'},blockEntity:'{Byte:1b,Long:9L}'},
                    {x:0,y:-1,z:-3,block:'air'}]};
                const upgraded=builder.import_legacy_template(legacy);
                builder.save_template(upgraded,'legacy');
                const loaded=builder.load_template('legacy');
                builder.paste_structure(loaded,10,20,30);
                return {template:upgraded,loaded:loaded,cells:snapshot()};
                """);
        JsonObject t=actual.getAsJsonObject("template");
        assertEquals("openallay:structure",t.get("format").getAsString());
        assertArray(t.getAsJsonArray("size"),3,2,2);
        assertTrue(t.get("includesAir").getAsBoolean());
        assertEquals(3,t.getAsJsonArray("palette").size());
        assertEquals(t,actual.get("loaded"));
        assertArray(t.getAsJsonArray("blocks").get(0).getAsJsonObject().getAsJsonArray("pos"),0,0,0);
        assertEquals("minecraft:oak_planks",actual.getAsJsonObject("cells").getAsJsonObject("10,20,30").get("id").getAsString());
        JsonObject chest=actual.getAsJsonObject("cells").getAsJsonObject("12,21,31");
        assertEquals("modded:chest",chest.get("id").getAsString());
        assertEquals("{Byte:1b,Long:9L}",chest.get("blockEntity").getAsString());
    }

    @Test
    void legacyPaletteAndNamedDimensionsUpgradeWithoutChangingCanonicalTemplates() {
        JsonObject actual=BuilderJsFixture.evaluate(ASYMMETRIC+"""
                const legacy={dimensions:{width:2,height:1,depth:1},
                    palette:['stone',{id:'minecraft:oak_log',properties:{axis:'z'}}],
                    blocks:[{x:0,y:0,z:0,block:0},{pos:[1,0,0],state:1}]};
                return {legacy:builder.import_legacy_template(legacy),
                        canonical:builder.import_legacy_template(template),original:template};
                """);
        assertArray(actual.getAsJsonObject("legacy").getAsJsonArray("size"),2,1,1);
        assertEquals(2,actual.getAsJsonObject("legacy").getAsJsonArray("blocks").size());
        assertEquals(actual.get("original"),actual.get("canonical"));
    }

    @Test
    void unsupportedNativeBlockEntityTransformFailsBeforeThePasteWritesAnything() {
        JsonObject actual=BuilderJsFixture.evaluate(ASYMMETRIC+"""
                const original=backend.transformState;
                backend.transformState=function(json,rotation,mirror) {
                    if(JSON.parse(json).blockEntity!==undefined) throw new Error('unsupported_block_entity_transform');
                    return original(json,rotation,mirror);
                };
                let error='';
                try {builder.paste_structure(template,0,5,0,{rotation:90});}catch(e){error=String(e);}
                return {error:error,count:writes.length,regions:regions.length,connections:connections.length};
                """);
        assertTrue(actual.get("error").getAsString().contains("unsupported_block_entity_transform"));
        assertEquals(0,actual.get("count").getAsInt());
        assertEquals(0,actual.get("regions").getAsInt());
        assertEquals(0,actual.get("connections").getAsInt());
    }

    @Test
    void exactUpstreamLegacyShapeKeepsPalettePropertiesAndScanProvenance() {
        JsonObject actual=BuilderJsFixture.evaluate("""
                const upstream={size:[2,2,3],block_count:3,blocks:[
                    {pos:[0,0,0],name:'oak_plank',props:{}},
                    {pos:[1,0,0],name:'oak_plank',props:{}},
                    {pos:[1,1,2],name:'oak_stairs',props:{facing:'west',half:'top'}}],
                    meta:{scanned_from:[101,62,-44],scanned_to:[102,63,-42]}};
                const upgraded=builder.import_legacy_template(upstream);
                builder.save_template(upgraded,'upstream-export');
                return {template:upgraded,loaded:builder.load_template('upstream-export'),
                        provenance:upstream.meta};
                """);
        JsonObject t=actual.getAsJsonObject("template");
        assertArray(t.getAsJsonArray("size"),2,2,3);
        assertEquals(3,t.getAsJsonArray("blocks").size());
        assertEquals(2,t.getAsJsonArray("palette").size());
        assertEquals("minecraft:oak_planks",t.getAsJsonArray("palette").get(0).getAsJsonObject().get("id").getAsString());
        JsonObject stairs=t.getAsJsonArray("palette").get(1).getAsJsonObject();
        assertEquals("west",stairs.getAsJsonObject("properties").get("facing").getAsString());
        assertEquals("top",stairs.getAsJsonObject("properties").get("half").getAsString());
        assertEquals(t,actual.get("loaded"));
        assertTrue(t.getAsJsonObject("metadata").get("importedLegacy").getAsBoolean());
        assertEquals(actual.get("provenance"),t.getAsJsonObject("metadata").get("legacy"));
    }

    private static int[] transform(int[] p,int rotation,String mirror) {
        // Rotate centered integer vectors, then translate to the positive output bounds.
        int centeredX=2*p[0]-2,centeredZ=2*p[2]-3;
        if(mirror.equals("front_back")) centeredX=-centeredX;
        if(mirror.equals("left_right")) centeredZ=-centeredZ;
        for(int turn=0;turn<rotation/90;turn++) {
            int previousX=centeredX;centeredX=-centeredZ;centeredZ=previousX;
        }
        int width=rotation%180==0?3:4,depth=rotation%180==0?4:3;
        return new int[]{(centeredX+width-1)/2,p[1],(centeredZ+depth-1)/2};
    }

    private static String direction(String name,int rotation,String mirror) {
        int[] vector=switch(name) {
            case "north"->new int[]{0,-1};case "south"->new int[]{0,1};
            case "east"->new int[]{1,0};case "west"->new int[]{-1,0};
            default->throw new IllegalArgumentException(name);
        };
        if(mirror.equals("front_back")) vector[0]=-vector[0];
        if(mirror.equals("left_right")) vector[1]=-vector[1];
        for(int turn=0;turn<rotation/90;turn++) {int previousX=vector[0];vector[0]=-vector[1];vector[1]=previousX;}
        return vector[0]>0?"east":vector[0]<0?"west":vector[1]>0?"south":"north";
    }

    private static void assertArray(JsonArray actual,int... expected) {
        assertEquals(expected.length,actual.size());
        for(int i=0;i<expected.length;i++) assertEquals(expected[i],actual.get(i).getAsInt(),"index "+i);
    }
}
