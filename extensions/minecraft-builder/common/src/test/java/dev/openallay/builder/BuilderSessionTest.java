package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockSpec;
import dev.openallay.builder.storage.OperationJournal;
import dev.openallay.context.EvidenceMetadata;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import net.minecraft.core.BlockPos;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BuilderSessionTest {
    @TempDir Path directory;
    static final String AIR = "{\"id\":\"minecraft:air\",\"properties\":{}}";
    static final String STONE = "{\"id\":\"minecraft:stone\",\"properties\":{}}";
    static final String DIRT = "{\"id\":\"minecraft:dirt\",\"properties\":{}}";
    static final String BOUNDS = "{\"minX\":0,\"minY\":1,\"minZ\":0,\"maxX\":0,\"maxY\":1,\"maxZ\":0}";
    final Invocation invocation = new Invocation();
    BuilderSession session(Backend backend) { return new BuilderSession(invocation,backend,new OwnerThreadBridge(() -> {}, () -> false),"{}"); }
    static JsonObject status(BuilderSession session) { return JsonParser.parseString(session.status()).getAsJsonObject(); }

    @Test void explicitDimensionMismatchFailsBeforeArtifactCreation() {
        Backend backend = new Backend(directory.resolve("absent"));
        assertThrows(BuilderException.class, () -> new BuilderSession(invocation,backend,new OwnerThreadBridge(() -> {},()->false),"{\"dimension\":\"minecraft:the_nether\"}"));
        assertFalse(Files.exists(backend.artifacts()));
    }
    @Test void connectionRepairKeepsOriginalImageAcrossPreparationRace() {
        Backend backend = new Backend(directory);
        backend.repair = STONE;
        backend.raceDuringRepair = true;
        BuilderSession session = session(backend);
        BuilderException failure = assertThrows(BuilderException.class, () -> session.updateConnections(BOUNDS));
        assertEquals("concurrent_edit",failure.code());
        assertEquals(DIRT,backend.read(new BlockPos(0,1,0)));
        assertEquals(0,backend.writeCount);
    }
    @Test void laterPreparationFailureCannotBeFinishedAsCompleted() throws Exception {
        Backend backend = new Backend(directory);
        BuilderSession session = session(backend);
        session.write(0,1,0,STONE);
        backend.failPreview = true;
        assertThrows(BuilderException.class, () -> session.write(1,1,0,STONE));
        assertEquals("failed-partial",status(session).get("state").getAsString());
        assertThrows(BuilderException.class,session::finish);
        invocation.success = true; session.closeAfterInvocation();
        assertEquals("failed-partial",status(session).get("state").getAsString());
        assertEquals(OperationJournal.Status.FAILED,new OperationJournal(directory.resolve("journals")).list().getFirst().status());
    }
    @Test void appliedThenNativeFailurePersistsActualAndCountsPartial() throws Exception {
        Backend backend = new Backend(directory);
        backend.failAfterWrite = true;
        BuilderSession session = session(backend);
        assertThrows(BuilderException.class, () -> session.write(0,1,0,STONE));
        assertEquals(1,status(session).get("writes").getAsInt());
        var journal = new OperationJournal(directory.resolve("journals")).list().getFirst();
        assertEquals(BlockSpec.fromJson(DIRT),journal.entries().getFirst().verified());
        assertEquals(OperationJournal.Status.FAILED,journal.status());
    }
    @Test void explicitUndoAfterCaughtPartialFailureStartsANewAuthorizedOperation() {
        Backend backend=new Backend(directory); backend.failAfterWrite=true;
        BuilderSession session=session(backend);
        assertThrows(BuilderException.class,()->session.write(0,1,0,STONE));
        backend.failAfterWrite=false;
        JsonObject result=JsonParser.parseString(session.undo()).getAsJsonObject();
        assertEquals(1,result.get("restored").getAsInt());
        assertEquals(AIR,backend.read(new BlockPos(0,1,0)));
    }
    @Test void successfulImplicitCleanupUsesOutcomeNotLifetimeCancellation() throws Exception {
        Backend backend = new Backend(directory);
        BuilderSession session = session(backend);
        session.write(0,1,0,STONE);
        invocation.cancelled = true; invocation.success = true;
        session.closeAfterInvocation();
        assertEquals("completed",status(session).get("state").getAsString());
        assertEquals(OperationJournal.Status.COMPLETED,new OperationJournal(directory.resolve("journals")).list().getFirst().status());
    }
    @Test void explicitCloseBeforeScriptReturnsCompletesVerifiedOperation() throws Exception {
        Backend backend=new Backend(directory);
        BuilderSession session=session(backend); session.write(0,1,0,STONE);
        session.close();
        assertEquals("completed",status(session).get("state").getAsString());
        assertEquals(OperationJournal.Status.COMPLETED,new OperationJournal(directory.resolve("journals")).list().getFirst().status());
    }
    @Test void failedImplicitCleanupDoesNotClaimSuccessfulCompletion() throws Exception {
        Backend backend = new Backend(directory);
        BuilderSession session = session(backend); session.write(0,1,0,STONE);
        invocation.cancelled=true; invocation.success=false; session.closeAfterInvocation();
        assertEquals("interrupted",status(session).get("state").getAsString());
        assertEquals(OperationJournal.Status.INTERRUPTED,new OperationJournal(directory.resolve("journals")).list().getFirst().status());
    }
    @Test void nativePhysicsRunsButDoesNotAttributeUnrelatedHaloChangesToUndo() throws Exception {
        Backend backend = new Backend(directory); backend.notifyChanges = true;
        BuilderSession session = session(backend); session.write(0,1,0,STONE);
        session.updateConnections(BOUNDS);
        assertEquals(27,backend.notifyCount);
        var observation=invocation.evidence.getLast();
        assertEquals("multi-slice-non-atomic",observation.details().get("openallay_builder:consistency"));
        assertEquals("explicit-writes-only",observation.details().get("openallay_builder:journal_coverage"));
        assertNotNull(observation.details().get("openallay_builder:capture_start"));
        assertNotNull(observation.details().get("openallay_builder:capture_end"));
        session.finish();
        var journal = new OperationJournal(directory.resolve("journals")).list().getFirst();
        assertEquals(1,journal.entries().size());
        assertEquals(BlockSpec.fromJson(STONE),journal.entries().getFirst().verified());
        BuilderSession undo=session(backend);
        JsonObject result=JsonParser.parseString(undo.undo(journal.id())).getAsJsonObject();
        assertEquals(1,result.getAsJsonArray("conflicts").size());
        assertEquals(DIRT,backend.read(new BlockPos(0,1,0)));
    }
    @Test void interveningEditBeforeNotifyIsRejectedAndNeverClaimedAsOurPostimage() {
        Backend backend=new Backend(directory);
        // Shape scan reads27, notify capture reads27, first comparison is read55.
        backend.raceOnRead=55;
        BuilderSession session=session(backend);
        BuilderException failure=assertThrows(BuilderException.class,()->session.updateConnections(BOUNDS));
        assertEquals("concurrent_edit",failure.code());
        assertEquals(0,backend.notifyCount);
        assertEquals(0,backend.writeCount);
    }

    @Test void knownUnattemptedRaceIntentCannotUndoSomeoneElsesEdit() throws Exception {
        Backend backend=new Backend(directory); backend.raceBeforeApply=true;
        BuilderSession session=session(backend);
        assertThrows(BuilderException.class,()->session.write(0,1,0,STONE));
        assertEquals(0,backend.writeCount);
        var journal=new OperationJournal(directory.resolve("journals")).list().getFirst();
        assertTrue(journal.entries().isEmpty());
        backend.raceBeforeApply=false;
        BuilderSession undo=session(backend); undo.undo(journal.id());
        assertEquals(STONE,backend.read(new BlockPos(0,1,0)));
        assertEquals(0,backend.writeCount);
    }

    @Test void pureTransformEvidenceIsInputOnlyNotWorldObservation() {
        Backend backend = new Backend(directory);
        BuilderSession session = session(backend); session.transformState(STONE,90,"none");
        var evidence = invocation.evidence.getLast();
        assertEquals(dev.openallay.context.DataAuthority.INTEGRATION_API,evidence.authority());
        assertEquals("input-only",evidence.details().get("openallay_builder:coverage"));
    }

    @Test void largeWriteRegionAndUndoUseTwoOwnerActionsPerQuantumAndExactDurableImages() throws Exception {
        Backend backend=new Backend(directory);
        BuilderSession session=session(backend);
        com.google.gson.JsonArray changes=new com.google.gson.JsonArray();
        for(int i=0;i<2401;i++) {
            JsonObject change=new JsonObject();change.addProperty("x",i/49);change.addProperty("y",64);change.addProperty("z",i%49);change.add("state",JsonParser.parseString(STONE));changes.add(change);
        }
        JsonObject result=JsonParser.parseString(session.writeRegion(changes.toString())).getAsJsonObject();
        assertEquals(2401,result.get("verified").getAsInt());assertEquals(2401,backend.writeCount);
        assertEquals(20,backend.callCount,"Ten preparation slices and ten apply slices, not per-block dispatch");
        assertEquals(1,invocation.evidence.stream().filter(e->e.sourceId().equals("openallay_builder:write-readback")).count());
        session.finish();
        var original=new OperationJournal(directory.resolve("journals")).list().getFirst();
        assertEquals(2401,original.entries().size());
        for(var entry:original.entries()) {assertEquals(BlockSpec.fromJson(AIR),entry.before());assertEquals(BlockSpec.fromJson(STONE),entry.verified());assertFalse(entry.pending());}
        int before=backend.callCount;
        JsonObject undone=JsonParser.parseString(session.undo(original.id())).getAsJsonObject();
        assertEquals(2401,undone.get("restored").getAsInt());assertEquals(0,undone.getAsJsonArray("conflicts").size());
        assertEquals(20,backend.callCount-before);
        for(String value:backend.blocks.values())assertEquals(AIR,value);
        var journals=new OperationJournal(directory.resolve("journals")).list();
        var undo=journals.stream().filter(o->!o.id().equals(original.id())).findFirst().orElseThrow();
        assertEquals(original.reverseEntries().stream().map(OperationJournal.Entry::position).toList(),undo.entries().stream().map(OperationJournal.Entry::position).toList());
        for(var entry:undo.entries()){assertEquals(BlockSpec.fromJson(STONE),entry.before());assertEquals(BlockSpec.fromJson(AIR),entry.verified());}
    }

    @Test void derivedExpectedImageRejectsExternalContainerBeforePreflightWithoutJournalIntent() throws Exception {
        Backend backend=new Backend(directory);backend.blocks.put(new BlockPos(0,1,0),DIRT);
        BuilderSession session=session(backend);
        BuilderException failure=assertThrows(BuilderException.class,()->session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+AIR+",\"expectedBefore\":"+STONE+"}]"));
        assertEquals("concurrent_edit",failure.code());assertEquals(0,backend.writeCount);
        assertEquals(DIRT,backend.read(new BlockPos(0,1,0)));
        assertTrue(new OperationJournal(directory.resolve("journals")).list().isEmpty());
    }

    @Test void duplicatesSeparatedAcrossQuantumAreOneFinalImagePlanWithOriginalBeforeAndUndo() throws Exception {
        List<String> applied=new ArrayList<>();
        Backend recording=new Backend(directory) {
            @Override public WriteOutcome write(BlockPos pos,String state) {
                if(pos.equals(new BlockPos(0,1,0)))applied.add(state);
                return super.write(pos,state);
            }
        };
        recording.blocks.put(new BlockPos(0,1,0),DIRT);
        BuilderSession session=session(recording);
        com.google.gson.JsonArray changes=new com.google.gson.JsonArray();
        JsonObject first=new JsonObject();first.addProperty("x",0);first.addProperty("y",1);first.addProperty("z",0);first.add("state",JsonParser.parseString(STONE));changes.add(first);
        for(int i=1;i<=300;i++) {
            JsonObject change=new JsonObject();change.addProperty("x",i);change.addProperty("y",1);change.addProperty("z",0);change.add("state",JsonParser.parseString(STONE));changes.add(change);
        }
        JsonObject last=first.deepCopy();last.add("state",JsonParser.parseString(AIR));changes.add(last);
        JsonObject result=JsonParser.parseString(session.writeRegion(changes.toString())).getAsJsonObject();
        assertEquals(301,result.get("verified").getAsInt());assertEquals(301,recording.writeCount);
        assertEquals(List.of(AIR),applied,"Discarded intermediate STONE assignment never invokes native replacement hooks");
        assertEquals(AIR,recording.read(new BlockPos(0,1,0)));
        session.finish();
        var original=new OperationJournal(directory.resolve("journals")).list().getFirst();
        assertEquals(301,original.entries().size());
        var entry=original.entries().getFirst();assertEquals(0,entry.position().x());
        assertEquals(BlockSpec.fromJson(DIRT),entry.before());assertEquals(BlockSpec.fromJson(AIR),entry.intended());assertEquals(BlockSpec.fromJson(AIR),entry.verified());
        session.undo(original.id());assertEquals(DIRT,recording.read(new BlockPos(0,1,0)));
        assertEquals(List.of(AIR,DIRT),applied);
    }

    @Test void batchedUndoReportsInitialAndBetweenSliceConflictsWithoutOverwritingExternalImages() throws Exception {
        Backend backend=new Backend(directory);BuilderSession session=session(backend);
        session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":1,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":2,\"y\":1,\"z\":0,\"state\":"+STONE+"}]");session.finish();
        var original=new OperationJournal(directory.resolve("journals")).list().getFirst();
        backend.blocks.put(new BlockPos(2,1,0),DIRT);
        backend.raceOnCall=backend.callCount+2;backend.racePosition=new BlockPos(1,1,0);
        JsonObject result=JsonParser.parseString(session.undo(original.id())).getAsJsonObject();
        assertEquals(1,result.get("restored").getAsInt());assertEquals(2,result.getAsJsonArray("conflicts").size());
        assertEquals(AIR,backend.read(new BlockPos(0,1,0)));assertEquals(DIRT,backend.read(new BlockPos(1,1,0)));assertEquals(DIRT,backend.read(new BlockPos(2,1,0)));
        var undo=new OperationJournal(directory.resolve("journals")).list().stream().filter(o->!o.id().equals(original.id())).findFirst().orElseThrow();
        assertEquals(1,undo.entries().size());assertEquals(0,undo.entries().getFirst().position().x());
    }

    @Test void batchedWriteFailureRecordsActualPartialProgressAndAbortsOnlyKnownUnstarted() throws Exception {
        Backend backend=new Backend(directory);backend.failAfterWrite=true;BuilderSession session=session(backend);
        assertThrows(BuilderException.class,()->session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":1,\"y\":1,\"z\":0,\"state\":"+STONE+"}]"));
        var journal=new OperationJournal(directory.resolve("journals")).list().getFirst();
        assertEquals(1,journal.entries().size());assertEquals(BlockSpec.fromJson(DIRT),journal.entries().getFirst().verified());
        assertEquals(1,invocation.evidence.size());assertEquals("1",invocation.evidence.getFirst().details().get("openallay_builder:count"));
    }

    static class Invocation implements SessionInvocation {
        boolean cancelled,success;
        List<EvidenceMetadata> evidence=new ArrayList<>();
        public boolean cancelled(){return cancelled;}
        public boolean completedSuccessfully(){return success;}
        public void evidence(EvidenceMetadata item){evidence.add(item);}
        public String loader(){return "test";}
    }
    static class Backend implements BuilderBackend {
        final Path path;
        Map<BlockPos,String> blocks=new HashMap<>();
        String repair;
        boolean raceDuringRepair,failPreview,failAfterWrite,notifyChanges,raceBeforeApply;
        int readCount;
        int raceOnRead=-1;
        int writeCount,notifyCount,callCount;
        int raceOnCall=-1;
        BlockPos racePosition;
        Backend(Path path){this.path=path;}
        public <T>T call(Callable<T> action){callCount++;if(callCount==raceOnCall)blocks.put(racePosition,DIRT);try{return action.call();}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new RuntimeException(failure);}}
        public void validatePosition(BlockPos pos){}
        public String read(BlockPos pos){readCount++;if((raceBeforeApply && readCount==2)||readCount==raceOnRead)blocks.put(pos,STONE);return blocks.getOrDefault(pos,AIR);}
        public String preview(BlockPos pos,String state){if(failPreview)throw new BuilderException("invalid_state","bad preview");return state;}
        public WriteOutcome write(BlockPos pos,String state){writeCount++;blocks.put(pos,failAfterWrite?DIRT:state);return new WriteOutcome(read(pos),true,failAfterWrite?new BuilderException("placement_failed","native hook changed target"):null);}
        public String transform(String state,int degrees,String mirror){return state;}
        public String repairedState(BlockPos pos){if(pos.equals(new BlockPos(0,1,0))){if(raceDuringRepair)blocks.put(pos,DIRT);return repair;}return null;}
        public void notifyNeighbours(BlockPos pos){
            notifyCount++;
            if(notifyChanges) for(int x=-1;x<=1;x++)for(int y=0;y<=2;y++)for(int z=-1;z<=1;z++)blocks.put(new BlockPos(x,y,z),DIRT);
        }
        public String context(){return "{\"minY\":-64,\"maxY\":320}";}
        public String dimension(){return "minecraft:overworld";}
        public String worldId(){return "test-world";}
        public Path artifacts(){return path;}
    }
}
