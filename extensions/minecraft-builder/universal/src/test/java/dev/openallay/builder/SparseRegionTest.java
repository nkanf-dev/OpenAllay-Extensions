package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonParser;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import dev.openallay.builder.storage.BlockPosition;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SparseRegionTest {
    @TempDir Path directory;
    static class Backend extends BuilderSessionTest.Backend {
        int proofs;
        boolean unavailable;
        Set<BlockPosition> blockEntities=new HashSet<>();
        Backend(Path directory){super(directory);}
        @Override public boolean canonicalAir(BuilderBounds tile) {
            proofs++;
            if(unavailable)throw new BuilderException("chunk_unavailable","proof cannot observe an unloaded chunk");
            for(BlockPosition pos:blockEntities)if(in(tile,pos))return false;
            for(Map.Entry<BlockPosition, String> entry:blocks.entrySet())if(in(tile,entry.getKey()) && !entry.getValue().equals(BuilderSessionTest.AIR))return false;
            return true;
        }
        static boolean in(BuilderBounds b,BlockPosition p){return p.x()>=b.minX()&&p.x()<=b.maxX()&&p.y()>=b.minY()&&p.y()<=b.maxY()&&p.z()>=b.minZ()&&p.z()<=b.maxZ();}
    }
    BuilderSession session(Backend backend,BuilderSessionTest.Invocation invocation) {
        return new BuilderSession(invocation,backend,new OwnerThreadBridge(()->{},()->false),"{}");
    }
    @Test void tallCanonicalRegionUsesSectionProofsWithoutPretendingVoxelReads() {
        Backend backend=new Backend(directory);
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=session(backend,invocation);
        String json="{\"minX\":-17,\"maxX\":17,\"minY\":-17,\"maxY\":63,\"minZ\":-1,\"maxZ\":16,\"omitAir\":true}";
        assertEquals(0,JsonParser.parseString(session.readRegion(json)).getAsJsonArray().size());
        assertEquals(72,backend.proofs,"4 x-sections, 3 z-sections, 6 y-sections including clipped negative borders");
        assertEquals(0,backend.readCount);
        long volume=35L*81*18;
        assertEquals(volume,BuilderSessionTest.status(session).get("paletteProvenCells").getAsLong());
        assertEquals(0,BuilderSessionTest.status(session).get("reads").getAsInt());
        assertEquals(Long.toString(volume),FixtureValues.last(invocation.evidence).details().get("openallay_builder:count"));
        assertTrue(FixtureValues.last(invocation.evidence).details().get("openallay_builder:coverage").contains("palette-proofs"));
    }
    @Test void pendingOrOrphanBlockEntityAndNoncanonicalAirFallbackKeepsExactIdentityAndDenseOrder() {
        Backend backend=new Backend(directory);
        String cave="{\"id\":\"minecraft:cave_air\",\"properties\":{}}";
        String custom="{\"id\":\"custom:air\",\"properties\":{}}";
        String orphan="{\"id\":\"minecraft:air\",\"properties\":{},\"blockEntity\":\"{id:'custom:orphan'}\"}";
        backend.blocks.put(new BlockPosition(16,0,0),cave);
        backend.blocks.put(new BlockPosition(0,1,0),custom);
        backend.blocks.put(new BlockPosition(1,1,0),orphan);backend.blockEntities.add(new BlockPosition(1,1,0));
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=session(backend,invocation);
        com.google.gson.JsonArray result = JsonParser.parseString(session.readRegion("{\"minX\":0,\"maxX\":16,\"minY\":0,\"maxY\":1,\"minZ\":0,\"maxZ\":0,\"omitAir\":true}")).getAsJsonArray();
        assertEquals(3,result.size());
        assertEquals(cave,result.get(0).getAsJsonObject().get("state").toString());
        assertEquals(custom,result.get(1).getAsJsonObject().get("state").toString());
        assertEquals(orphan,result.get(2).getAsJsonObject().get("state").toString());
        assertEquals(34,backend.readCount);
    }
    @Test void aPartiallyReadTileIsReprovedRatherThanCachingItsWorldStateAcrossOwnerActions() {
        Backend backend=new Backend(directory) { @Override public long sliceDeadline(){return 0;} };
        backend.blocks.put(new BlockPosition(0,0,0),BuilderSessionTest.STONE);
        SparseRegion.Cursor cursor=new SparseRegion.Cursor(new BuilderBounds(0,0,0,2,0,0));
        SparseRegion.Slice first=cursor.capture(backend,8192);
        assertEquals(1,first.voxelReads());assertEquals(1,first.cells().size());
        backend.blocks.remove(new BlockPosition(0,0,0));
        SparseRegion.Slice second=cursor.capture(backend,8192);
        assertEquals(2,second.paletteProven());assertEquals(0,second.voxelReads());
        assertTrue(cursor.done());assertEquals(2,backend.proofs);
    }

    @Test void canonicalAirShapeRepairSkipsOnlyProvenNativeNoOpAndNeverBroadcastsPhysics() {
        Backend backend=new Backend(directory) {
            @Override public String repairedState(BlockPosition pos){throw new AssertionError("Proven canonical AIR shape is a native identity");}
        };
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=session(backend,invocation);
        com.google.gson.JsonObject result = JsonParser.parseString(session.updateConnections("{\"minX\":0,\"maxX\":31,\"minY\":0,\"maxY\":31,\"minZ\":0,\"maxZ\":31}")).getAsJsonObject();
        assertEquals(0,result.get("changed").getAsInt());
        assertEquals(34L*34*34,result.get("verified").getAsLong());
        assertEquals(0,backend.notifyCount);assertEquals(0,backend.readCount);
        assertEquals(34L*34*34,BuilderSessionTest.status(session).get("paletteProvenCells").getAsLong());
    }

    @Test void proofIsRepeatedAfterOwnerYieldAndCannotHideLaterLoadedChunkFailure() {
        Backend backend=new Backend(directory) {
            @Override public long sliceDeadline(){return 0;}
            @Override public <T>T call(java.util.concurrent.Callable<T> action) {
                if(callCount==1){blocks.put(new BlockPosition(16,0,0),BuilderSessionTest.STONE);}
                return super.call(action);
            }
        };
        BuilderSessionTest.Invocation invocation = new BuilderSessionTest.Invocation();
        BuilderSession session=session(backend,invocation);
        com.google.gson.JsonArray result = JsonParser.parseString(session.readRegion("{\"minX\":0,\"maxX\":16,\"minY\":0,\"maxY\":0,\"minZ\":0,\"maxZ\":0,\"omitAir\":true}")).getAsJsonArray();
        assertEquals(1,result.size());assertEquals(16,result.get(0).getAsJsonObject().get("x").getAsInt());
        backend.unavailable=true;
        int before=invocation.evidence.size();
        assertThrows(BuilderException.class,()->session.readRegion("{\"minX\":0,\"maxX\":16,\"minY\":0,\"maxY\":0,\"minZ\":0,\"maxZ\":0,\"omitAir\":true}"));
        assertEquals(before,invocation.evidence.size(),"Incomplete proof cannot publish complete sparse evidence");
    }
}
