package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockSpec;
import dev.openallay.builder.storage.OperationJournal;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class TerrainScanTest {
    @TempDir Path directory;
    private static final BlockSpec AIR = new BlockSpec("minecraft:air",Map.of());
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone",Map.of());

    private static final class Backend extends BuilderSessionTest.Backend {
        final Map<BlockPos,BlockSpec> terrain = new HashMap<>();
        int calls, maxSliceReads, sliceReads, topQueries;
        int top = 319;
        int ground = 64;
        boolean flat = true;
        boolean cancelAfterSlice;
        Runnable requireActive = () -> {};
        Backend(Path path) { super(path); }
        @Override public <T> T call(java.util.concurrent.Callable<T> action) {
            calls++; sliceReads=0;
            T result=super.call(action);
            maxSliceReads=Math.max(maxSliceReads,sliceReads);
            if(cancelAfterSlice && calls==1) requireActive=() -> {throw new BuilderException("cancelled","cancelled between slices");};
            return result;
        }
        @Override public int terrainTop(int x,int z,int minY,int maxY) { topQueries++; return Math.min(top,maxY-1); }
        @Override public BlockSpec terrainState(BlockPos pos) {
            requireActive.run(); sliceReads++; readCount++;
            if(flat) return pos.getY()<=ground ? STONE : AIR;
            if(terrain.containsKey(pos)) return terrain.get(pos);
            return AIR;
        }
    }

    private static String command(int x1,int z1,int x2,int z2,int minY,int maxY,boolean ground) {
        JsonObject request = new JsonObject();
        request.addProperty("minX",x1); request.addProperty("minZ",z1); request.addProperty("maxX",x2); request.addProperty("maxZ",z2);
        request.addProperty("minY",minY); request.addProperty("maxY",maxY); request.addProperty("groundOnly",ground);
        JsonArray accepted = new JsonArray(); accepted.add("minecraft:stone"); accepted.add("custom:soil");
        request.add("ground",accepted); request.add("vegetation",new JsonArray());
        return request.toString();
    }

    private BuilderSession session(Backend backend,BuilderSessionTest.Invocation invocation) {
        OwnerThreadBridge bridge = new OwnerThreadBridge(() -> backend.requireActive.run(),()->false);
        return new BuilderSession(invocation,backend,bridge,"{}");
    }

    @Test void fullHeight49By49ScanHasExactIndexingAndHundredsFewerOwnerRoundTripsWithoutCaps() throws Exception {
        Backend source = new Backend(directory);
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session = session(source,invocation);
        JsonArray columns=JsonParser.parseString(session.scanColumns(command(-24,-24,24,24,-64,320,true))).getAsJsonArray();
        assertEquals(2401,columns.size());
        for(int i=0;i<columns.size();i++) {
            JsonObject column=columns.get(i).getAsJsonObject();
            assertEquals(-24+i/49,column.get("x").getAsInt());
            assertEquals(-24+i%49,column.get("z").getAsInt());
            assertEquals(64,column.get("y").getAsInt());
            assertEquals("minecraft:stone",column.get("block").getAsString());
        }
        long legacyReads=2401L*(320-64);
        assertEquals(legacyReads,source.readCount,"Full-height fallback must still inspect all cells down to ground");
        assertEquals((legacyReads+2401+255)/256,source.calls,"Only cooperative owner slices, never one call per cell");
        assertTrue(source.calls*200L<legacyReads);
        assertTrue(source.maxSliceReads<=256);
        assertEquals(0,source.writeCount);
        session.finish();
        assertTrue(new OperationJournal(directory.resolve("journals")).list().isEmpty());
        assertEquals(legacyReads,BuilderSessionTest.status(session).get("reads").getAsLong());
        var evidence=invocation.evidence.getLast();
        assertEquals("multi-slice-non-atomic",evidence.details().get("openallay_builder:consistency"));
        assertNotNull(evidence.details().get("openallay_builder:capture_start"));
        assertNotNull(evidence.details().get("openallay_builder:capture_end"));
    }

    @Test void conservativeHeightmapUpperBoundSkipsAirButStillTestsGroundBelowBuildingsWaterAndTrees() {
        Backend source = new Backend(directory); source.flat=false; source.top=8;
        source.terrain.put(new BlockPos(0,8,0),new BlockSpec("minecraft:chest",Map.of("facing","north"),"{Items:[]}"));
        source.terrain.put(new BlockPos(0,7,0),new BlockSpec("minecraft:water",Map.of("level","0")));
        source.terrain.put(new BlockPos(0,6,0),new BlockSpec("minecraft:oak_leaves",Map.of("persistent","false")));
        source.terrain.put(new BlockPos(0,-3,0),new BlockSpec("custom:soil",Map.of("variant","damp")));
        BuilderSession session=session(source,new BuilderSessionTest.Invocation());
        JsonObject ground=JsonParser.parseString(session.scanColumns(command(0,0,0,0,-6,9,true))).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(-3,ground.get("y").getAsInt());
        assertEquals("damp",ground.getAsJsonObject("properties").get("variant").getAsString());
        JsonObject terrain=JsonParser.parseString(session.scanColumns(command(0,0,0,0,-6,513,false))).getAsJsonArray().get(0).getAsJsonObject();
        assertEquals(8,terrain.get("y").getAsInt());
        assertEquals("minecraft:chest",terrain.get("block").getAsString());
        assertEquals(Set.of("x","z","y","block","properties"),terrain.keySet(),"Scans do not copy BE payloads");
        assertEquals(2,source.calls);
    }

    @Test void heightmap49By49RequiresOnlyNineteenOwnerSlicesAndNoTruncatedRows() {
        Backend source=new Backend(directory); source.top=64;
        BuilderSession session=session(source,new BuilderSessionTest.Invocation());
        JsonArray columns=JsonParser.parseString(session.scanColumns(command(-24,-24,24,24,-64,320,true))).getAsJsonArray();
        assertEquals(2401,columns.size());
        assertEquals(2401,source.readCount);
        assertEquals(2401,source.topQueries);
        assertEquals(19,source.calls);
        assertEquals(24,columns.get(2400).getAsJsonObject().get("x").getAsInt());
        assertEquals(24,columns.get(2400).getAsJsonObject().get("z").getAsInt());
    }

    @Test void entireCustomHeightAndExclusiveWindowReturnEveryMissingColumnWithoutFabricatingAir() {
        Backend source=new Backend(directory); source.flat=false; source.top=4096;
        source.terrain.put(new BlockPos(1,-1700,0),new BlockSpec("custom:soil",Map.of("layer","deep")));
        source.terrain.put(new BlockPos(1,4096,0),STONE);
        BuilderSession session=session(source,new BuilderSessionTest.Invocation());
        JsonArray columns=JsonParser.parseString(session.scanColumns(command(0,0,2,0,-2048,4096,true))).getAsJsonArray();
        assertEquals(3,columns.size());
        assertTrue(columns.get(0).getAsJsonObject().get("y").isJsonNull());
        assertEquals(-1700,columns.get(1).getAsJsonObject().get("y").getAsInt());
        assertTrue(columns.get(2).getAsJsonObject().get("y").isJsonNull());
        assertEquals(4096+1700+2L*6144,source.readCount);
        source.terrain.put(new BlockPos(2,4095,0),null);
        assertThrows(BuilderException.class,()->session.scanColumns(command(0,0,2,0,-2048,4096,true)));
        assertEquals(0,source.writeCount);
    }

    @Test void cancellationAndOwnerFailureNeverReturnPartialScansOrCreateJournals() throws Exception {
        Backend source=new Backend(directory); source.flat=false; source.cancelAfterSlice=true;
        BuilderSession session=session(source,new BuilderSessionTest.Invocation());
        BuilderException failure=assertThrows(BuilderException.class,()->session.scanColumns(command(0,0,24,24,-64,320,true)));
        assertEquals("cancelled",failure.code());
        assertEquals(1,source.calls);
        assertTrue(source.readCount<=256);
        assertEquals(0,source.writeCount);
        assertTrue(new OperationJournal(directory.resolve("journals")).list().isEmpty());
    }

    @Test void predicateUsesExactIdsAndPropertiesNotNativeCollisionOrHeightmapSemantics() {
        Set<String> ground=Set.of("minecraft:stone","minecraft:water","minecraft:oak_log","minecraft:oak_leaves",
                "minecraft:stripped_oak_log","custom:leaves","custom:air","minecraft:fern");
        TerrainScan.Request request=new TerrainScan.Request(0,0,0,0,-180,513,true,ground,Set.of("minecraft:fern"));
        for(String id:List.of("minecraft:air","minecraft:cave_air","minecraft:void_air","minecraft:water","minecraft:oak_log","minecraft:fern"))
            assertFalse(request.accepts(new BlockSpec(id,Map.of())),id);
        assertFalse(request.accepts(new BlockSpec("minecraft:stone",Map.of("waterlogged","true"))));
        assertFalse(request.accepts(new BlockSpec("minecraft:oak_leaves",Map.of("persistent","false"))));
        assertTrue(request.accepts(new BlockSpec("minecraft:oak_leaves",Map.of("persistent","true"))));
        assertTrue(request.accepts(new BlockSpec("minecraft:stripped_oak_log",Map.of())));
        assertTrue(request.accepts(new BlockSpec("custom:leaves",Map.of())));
        assertTrue(request.accepts(new BlockSpec("custom:air",Map.of())));
        assertFalse(request.accepts(new BlockSpec("unknown:soil",Map.of())));
        TerrainScan.Request terrain=new TerrainScan.Request(0,0,0,0,-180,513,false,Set.of(),Set.of());
        assertTrue(terrain.accepts(new BlockSpec("custom:air",Map.of())));
        assertTrue(terrain.accepts(new BlockSpec("minecraft:water",Map.of())));
    }
}
