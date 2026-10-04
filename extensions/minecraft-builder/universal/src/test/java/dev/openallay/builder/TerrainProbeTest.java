package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockSpec;
import dev.openallay.api.extension.ExtensionEvidence.Completeness;
import java.nio.file.Path;
import dev.openallay.builder.storage.BlockPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerrainProbeTest {
    @TempDir Path directory;
    static String command(int minX,int maxX,Integer fixed,int clearance) {
        JsonObject request=new JsonObject();
        request.addProperty("minX",minX);request.addProperty("maxX",maxX);
        request.addProperty("minZ",0);request.addProperty("maxZ",0);
        request.addProperty("minY",0);request.addProperty("maxY",100);
        request.addProperty("worldMinY",-64);request.addProperty("worldMaxY",320);
        request.addProperty("groundOnly",true);request.addProperty("clearance",clearance);
        request.add("ground",JsonParser.parseString("[\"minecraft:stone\"]"));
        request.add("vegetation",JsonParser.parseString("[]"));
        if(fixed!=null)request.addProperty("fixedY",fixed);
        return request.toString();
    }
    static class Backend extends BuilderSessionTest.Backend {
        int topQueries,terrainReads;
        Backend(Path directory) {super(directory);}
        @Override public int terrainTop(int x,int z,int min,int max) {
            topQueries++;
            if(x==1)throw new BuilderException("chunk_unavailable","unloaded scan");
            return 64;
        }
        @Override public BlockSpec terrainState(BlockPosition pos) {
            terrainReads++;
            return new BlockSpec("minecraft:stone",FixtureValues.map());
        }
        @Override public String read(BlockPosition pos) {
            if(pos.x()==2 && pos.y()==65)throw new BuilderException("chunk_unavailable","unloaded headroom");
            return super.read(pos);
        }
    }
    @Test void demandTileDefersLocalErrorsByStageAndRetainsExactDenseStates() {
        Backend backend=new Backend(directory);
        backend.blocks.put(new BlockPosition(0,64,0),BuilderSessionTest.STONE);
        String cave="{\"id\":\"minecraft:cave_air\",\"properties\":{}}";
        backend.blocks.put(new BlockPosition(0,65,0),cave);
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=new BuilderSession(invocation,backend,new OwnerThreadBridge(()->{},()->false),"{}");
        com.google.gson.JsonArray result = JsonParser.parseString(session.probeColumns(command(0,2,null,2))).getAsJsonArray();
        assertEquals(3,result.size());
        com.google.gson.JsonObject first = result.get(0).getAsJsonObject();
        assertEquals(64,first.getAsJsonObject("column").get("y").getAsInt());
        assertEquals(3,first.getAsJsonArray("cells").size());
        assertEquals(cave,first.getAsJsonArray("cells").get(1).getAsJsonObject().get("state").toString());
        assertTrue(result.get(1).getAsJsonObject().get("column").isJsonNull(),"Scan failures wait for ground demand");
        assertTrue(result.get(1).getAsJsonObject().has("error"));
        com.google.gson.JsonObject third = result.get(2).getAsJsonObject();
        assertEquals(64,third.getAsJsonObject("column").get("y").getAsInt());
        assertEquals(0,third.getAsJsonArray("cells").size(),"Headroom failure never publishes partial cells as a complete snapshot");
        assertTrue(third.has("error"));
        assertEquals(Completeness.PARTIAL,FixtureValues.last(invocation.evidence).completeness());
        assertEquals("6",FixtureValues.last(invocation.evidence).details().get("openallay_builder:count"));
        assertEquals(0,backend.writeCount);
    }
    @Test void fixedHeightAtWorldCeilingReturnsColumnWithoutScanningOrReadingOutsideBounds() {
        Backend backend=new Backend(directory);
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=new BuilderSession(invocation,backend,new OwnerThreadBridge(()->{},()->false),"{}");
        com.google.gson.JsonObject result = JsonParser.parseString(session.probeColumns(command(0,0,319,2))).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(319,result.getAsJsonObject("column").get("y").getAsInt());
        assertTrue(result.getAsJsonObject("column").get("block").isJsonNull());
        assertEquals(0,result.getAsJsonArray("cells").size());
        assertFalse(result.has("error"));
        assertEquals(0,backend.topQueries);assertEquals(0,backend.readCount);
    }
    @Test void cooperativeCaptureNeverDuplicatesColumnsAndGlobalRevocationIsNotDeferred() {
        Backend backend=new Backend(directory) {
            @Override public long sliceDeadline(){return 0;}
            @Override public int terrainTop(int x,int z,int min,int max){return 64;}
        };
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=new BuilderSession(invocation,backend,new OwnerThreadBridge(()->{},()->false),"{}");
        com.google.gson.JsonArray result = JsonParser.parseString(session.probeColumns(command(0,2,null,2))).getAsJsonArray();
        assertEquals(3,result.size());
        for(int i=0;i<3;i++)assertEquals(i,result.get(i).getAsJsonObject().getAsJsonObject("column").get("x").getAsInt());
        Backend stale=new Backend(directory.resolve("stale")) {
            @Override public int terrainTop(int x,int z,int min,int max){throw new BuilderException("stale_session","connection replaced");}
        };
        BuilderSession failed=new BuilderSession(invocation,stale,new OwnerThreadBridge(()->{},()->false),"{}");
        BuilderException error=assertThrows(BuilderException.class,()->failed.probeColumns(command(0,2,null,2)));
        assertEquals("stale_session",error.code());
        assertEquals(1,invocation.evidence.size(),"Global failures never publish partial probe evidence");
    }
    @Test void callerCannotFabricateWorldHeightToHideHeadroomBounds() {
        Backend backend=new Backend(directory);
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=new BuilderSession(invocation,backend,new OwnerThreadBridge(()->{},()->false),"{}");
        assertThrows(BuilderException.class,()->session.probeColumns(command(0,0,null,2).replace("\"worldMaxY\":320","\"worldMaxY\":500")));
        assertEquals(0,backend.topQueries);
    }
}
