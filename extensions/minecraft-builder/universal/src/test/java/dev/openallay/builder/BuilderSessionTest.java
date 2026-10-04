package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockSpec;
import dev.openallay.builder.storage.OperationJournal;
import dev.openallay.api.extension.ExtensionEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import dev.openallay.builder.storage.BlockPosition;
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
        assertEquals(DIRT,backend.read(new BlockPosition(0,1,0)));
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
        assertEquals(OperationJournal.Status.FAILED,new OperationJournal(directory.resolve("journals")).list().get(0).status());
    }
    @Test void appliedThenNativeFailurePersistsActualAndCountsPartial() throws Exception {
        Backend backend = new Backend(directory);
        backend.failAfterWrite = true;
        BuilderSession session = session(backend);
        assertThrows(BuilderException.class, () -> session.write(0,1,0,STONE));
        assertEquals(1,status(session).get("writes").getAsInt());
        OperationJournal.Snapshot journal = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(BlockSpec.fromJson(DIRT),journal.entries().get(0).verified());
        assertEquals(OperationJournal.Status.FAILED,journal.status());
    }
    @Test void explicitUndoAfterCaughtPartialFailureStartsANewAuthorizedOperation() {
        Backend backend=new Backend(directory); backend.failAfterWrite=true;
        BuilderSession session=session(backend);
        assertThrows(BuilderException.class,()->session.write(0,1,0,STONE));
        backend.failAfterWrite=false;
        JsonObject result=JsonParser.parseString(session.undo()).getAsJsonObject();
        assertEquals(1,result.get("restored").getAsInt());
        assertEquals(AIR,backend.read(new BlockPosition(0,1,0)));
    }
    @Test void successfulImplicitCleanupUsesOutcomeNotLifetimeCancellation() throws Exception {
        Backend backend = new Backend(directory);
        BuilderSession session = session(backend);
        session.write(0,1,0,STONE);
        invocation.cancelled = true; invocation.success = true;
        session.closeAfterInvocation();
        assertEquals("completed",status(session).get("state").getAsString());
        assertEquals(OperationJournal.Status.COMPLETED,new OperationJournal(directory.resolve("journals")).list().get(0).status());
    }
    @Test void explicitCloseBeforeScriptReturnsCompletesVerifiedOperation() throws Exception {
        Backend backend=new Backend(directory);
        BuilderSession session=session(backend); session.write(0,1,0,STONE);
        session.close();
        assertEquals("completed",status(session).get("state").getAsString());
        assertEquals(OperationJournal.Status.COMPLETED,new OperationJournal(directory.resolve("journals")).list().get(0).status());
    }
    @Test void failedImplicitCleanupDoesNotClaimSuccessfulCompletion() throws Exception {
        Backend backend = new Backend(directory);
        BuilderSession session = session(backend); session.write(0,1,0,STONE);
        invocation.cancelled=true; invocation.success=false; session.closeAfterInvocation();
        assertEquals("interrupted",status(session).get("state").getAsString());
        assertEquals(OperationJournal.Status.INTERRUPTED,new OperationJournal(directory.resolve("journals")).list().get(0).status());
    }
    @Test void nativePhysicsRunsButDoesNotAttributeUnrelatedHaloChangesToUndo() throws Exception {
        Backend backend = new Backend(directory); backend.notifyChanges = true;
        BuilderSession session = session(backend); session.write(0,1,0,STONE);
        session.syncPhysics(BOUNDS);
        assertEquals(27,backend.notifyCount);
        ExtensionEvidence observation = FixtureValues.last(invocation.evidence);
        assertEquals("multi-slice-non-atomic",observation.details().get("openallay_builder:consistency"));
        assertEquals("explicit-writes-only",observation.details().get("openallay_builder:journal_coverage"));
        assertNotNull(observation.details().get("openallay_builder:capture_start"));
        assertNotNull(observation.details().get("openallay_builder:capture_end"));
        session.finish();
        OperationJournal.Snapshot journal = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(1,journal.entries().size());
        assertEquals(BlockSpec.fromJson(STONE),journal.entries().get(0).verified());
        BuilderSession undo=session(backend);
        JsonObject result=JsonParser.parseString(undo.undo(journal.id())).getAsJsonObject();
        assertEquals(1,result.getAsJsonArray("conflicts").size());
        assertEquals(DIRT,backend.read(new BlockPosition(0,1,0)));
    }
    @Test void interveningEditBeforeNotifyIsRejectedAndNeverClaimedAsOurPostimage() {
        Backend backend=new Backend(directory);
        // Explicit physics captures 27 cells; the first comparison is read28.
        backend.raceOnRead=28;
        BuilderSession session=session(backend);
        BuilderException failure=assertThrows(BuilderException.class,()->session.syncPhysics(BOUNDS));
        assertEquals("concurrent_edit",failure.code());
        assertEquals(0,backend.notifyCount);
        assertEquals(0,backend.writeCount);
    }

    @Test void knownUnattemptedRaceIntentCannotUndoSomeoneElsesEdit() throws Exception {
        Backend backend=new Backend(directory); backend.raceBeforeApply=true;
        BuilderSession session=session(backend);
        assertThrows(BuilderException.class,()->session.write(0,1,0,STONE));
        assertEquals(0,backend.writeCount);
        OperationJournal.Snapshot journal = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertTrue(journal.entries().isEmpty());
        backend.raceBeforeApply=false;
        BuilderSession undo=session(backend); undo.undo(journal.id());
        assertEquals(STONE,backend.read(new BlockPosition(0,1,0)));
        assertEquals(0,backend.writeCount);
    }

    @Test void pureTransformEvidenceIsInputOnlyNotWorldObservation() {
        Backend backend = new Backend(directory);
        BuilderSession session = session(backend); session.transformState(STONE,90,"none");
        ExtensionEvidence evidence = FixtureValues.last(invocation.evidence);
        assertEquals(dev.openallay.api.extension.ExtensionEvidence.Authority.INTEGRATION_API,evidence.authority());
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
        assertEquals(4,backend.callCount,"One preparation slice and three durable write slices, not per-block dispatch");
        assertEquals(1,invocation.evidence.stream().filter(e->e.sourceId().equals("openallay_builder:write-readback")).count());
        session.finish();
        OperationJournal.Snapshot original = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(2401,original.entries().size());
        for(OperationJournal.Entry entry:original.entries()) {assertEquals(BlockSpec.fromJson(AIR),entry.before());assertEquals(BlockSpec.fromJson(STONE),entry.verified());assertFalse(entry.pending());}
        int before=backend.callCount;
        JsonObject undone=JsonParser.parseString(session.undo(original.id())).getAsJsonObject();
        assertEquals(2401,undone.get("restored").getAsInt());assertEquals(0,undone.getAsJsonArray("conflicts").size());
        assertEquals(6,backend.callCount-before,"Three undo preparation slices and three apply slices");
        for(String value:backend.blocks.values())assertEquals(AIR,value);
        List<OperationJournal.Snapshot> journals = new OperationJournal(directory.resolve("journals")).list();
        OperationJournal.Snapshot undo = journals.stream().filter(o->!o.id().equals(original.id())).findFirst().orElseThrow(() -> new AssertionError("Expected matching fixture entry"));
        assertEquals(original.reverseEntries().stream().map(OperationJournal.Entry::position).collect(java.util.stream.Collectors.toList()),undo.entries().stream().map(OperationJournal.Entry::position).collect(java.util.stream.Collectors.toList()));
        for(OperationJournal.Entry entry:undo.entries()){assertEquals(BlockSpec.fromJson(STONE),entry.before());assertEquals(BlockSpec.fromJson(AIR),entry.verified());}
    }

    @Test void derivedExpectedImageRejectsExternalContainerBeforePreflightWithoutJournalIntent() throws Exception {
        Backend backend=new Backend(directory);backend.blocks.put(new BlockPosition(0,1,0),DIRT);
        BuilderSession session=session(backend);
        BuilderException failure=assertThrows(BuilderException.class,()->session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+AIR+",\"expectedBefore\":"+STONE+"}]"));
        assertEquals("concurrent_edit",failure.code());assertEquals(0,backend.writeCount);
        assertEquals(DIRT,backend.read(new BlockPosition(0,1,0)));
        assertTrue(new OperationJournal(directory.resolve("journals")).list().isEmpty());
    }

    @Test void duplicatesSeparatedAcrossQuantumAreOneFinalImagePlanWithOriginalBeforeAndUndo() throws Exception {
        List<String> applied=new ArrayList<>();
        Backend recording=new Backend(directory) {
            @Override public WriteOutcome write(BlockPosition pos,String state) {
                if(pos.equals(new BlockPosition(0,1,0)))applied.add(state);
                return super.write(pos,state);
            }
        };
        recording.blocks.put(new BlockPosition(0,1,0),DIRT);
        BuilderSession session=session(recording);
        com.google.gson.JsonArray changes=new com.google.gson.JsonArray();
        JsonObject first=new JsonObject();first.addProperty("x",0);first.addProperty("y",1);first.addProperty("z",0);first.add("state",JsonParser.parseString(STONE));changes.add(first);
        for(int i=1;i<=BuilderSession.QUANTUM;i++) {
            JsonObject change=new JsonObject();change.addProperty("x",i);change.addProperty("y",1);change.addProperty("z",0);change.add("state",JsonParser.parseString(STONE));changes.add(change);
        }
        JsonObject last=first.deepCopy();last.add("state",JsonParser.parseString(AIR));changes.add(last);
        JsonObject result=JsonParser.parseString(session.writeRegion(changes.toString())).getAsJsonObject();
        assertEquals(BuilderSession.QUANTUM+1,result.get("verified").getAsInt());assertEquals(BuilderSession.QUANTUM+1,recording.writeCount);
        assertEquals(FixtureValues.list(AIR),applied,"Discarded intermediate STONE assignment never invokes native replacement hooks");
        assertEquals(AIR,recording.read(new BlockPosition(0,1,0)));
        session.finish();
        OperationJournal.Snapshot original = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(BuilderSession.QUANTUM+1,original.entries().size());
        OperationJournal.Entry entry = original.entries().get(0);assertEquals(0,entry.position().x());
        assertEquals(BlockSpec.fromJson(DIRT),entry.before());assertEquals(BlockSpec.fromJson(AIR),entry.intended());assertEquals(BlockSpec.fromJson(AIR),entry.verified());
        session.undo(original.id());assertEquals(DIRT,recording.read(new BlockPosition(0,1,0)));
        assertEquals(FixtureValues.list(AIR,DIRT),applied);
    }

    @Test void batchedUndoReportsInitialAndBetweenSliceConflictsWithoutOverwritingExternalImages() throws Exception {
        Backend backend=new Backend(directory);BuilderSession session=session(backend);
        session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":1,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":2,\"y\":1,\"z\":0,\"state\":"+STONE+"}]");session.finish();
        OperationJournal.Snapshot original = new OperationJournal(directory.resolve("journals")).list().get(0);
        backend.blocks.put(new BlockPosition(2,1,0),DIRT);
        backend.raceOnCall=backend.callCount+2;backend.racePosition=new BlockPosition(1,1,0);
        JsonObject result=JsonParser.parseString(session.undo(original.id())).getAsJsonObject();
        assertEquals(1,result.get("restored").getAsInt());assertEquals(2,result.getAsJsonArray("conflicts").size());
        assertEquals(AIR,backend.read(new BlockPosition(0,1,0)));assertEquals(DIRT,backend.read(new BlockPosition(1,1,0)));assertEquals(DIRT,backend.read(new BlockPosition(2,1,0)));
        OperationJournal.Snapshot undo = new OperationJournal(directory.resolve("journals")).list().stream().filter(o->!o.id().equals(original.id())).findFirst().orElseThrow(() -> new AssertionError("Expected matching fixture entry"));
        assertEquals(1,undo.entries().size());assertEquals(0,undo.entries().get(0).position().x());
    }

    @Test void batchedWriteFailureRecordsActualPartialProgressAndAbortsOnlyKnownUnstarted() throws Exception {
        Backend backend=new Backend(directory);backend.failAfterWrite=true;BuilderSession session=session(backend);
        assertThrows(BuilderException.class,()->session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":1,\"y\":1,\"z\":0,\"state\":"+STONE+"}]"));
        OperationJournal.Snapshot journal = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(1,journal.entries().size());assertEquals(BlockSpec.fromJson(DIRT),journal.entries().get(0).verified());
        assertEquals(1,invocation.evidence.size());assertEquals("1",invocation.evidence.get(0).details().get("openallay_builder:count"));
    }

    @Test void sparseRegionOmitsOnlyCanonicalAirAndKeepsExactOrderAndEvidence() {
        Backend backend = new Backend(directory);
        String cave = "{\"id\":\"minecraft:cave_air\",\"properties\":{}}";
        String custom = "{\"id\":\"custom:air\",\"properties\":{\"variant\":\"visible\"}}";
        String chest = "{\"id\":\"minecraft:chest\",\"properties\":{},\"blockEntity\":\"{Items:[]}\"}";
        backend.blocks.put(new BlockPosition(2,1,0),cave);
        backend.blocks.put(new BlockPosition(0,1,1),custom);
        backend.blocks.put(new BlockPosition(1,2,0),chest);
        BuilderSession session = session(backend);
        String bounds = "{\"minX\":0,\"minY\":1,\"minZ\":0,\"maxX\":2,\"maxY\":2,\"maxZ\":1,\"omitAir\":true}";
        com.google.gson.JsonArray cells = JsonParser.parseString(session.readRegion(bounds)).getAsJsonArray();
        assertEquals(3,cells.size());
        assertEquals(cave,cells.get(0).getAsJsonObject().get("state").toString());
        assertEquals(custom,cells.get(1).getAsJsonObject().get("state").toString());
        assertEquals(chest,cells.get(2).getAsJsonObject().get("state").toString());
        assertEquals(12,backend.readCount,"All omitted voxels were still observed");
        assertEquals(12,status(session).get("reads").getAsInt());
        assertEquals("12",FixtureValues.last(invocation.evidence).details().get("openallay_builder:count"));
        assertNotNull(FixtureValues.last(invocation.evidence).details().get("openallay_builder:capture_start"));
        com.google.gson.JsonArray dense = JsonParser.parseString(session.readRegion(bounds.replace(",\"omitAir\":true",""))).getAsJsonArray();
        assertEquals(12,dense.size(),"The public dense contract is unchanged");
        assertEquals(0,backend.writeCount);
    }

    @Test void sparseCaptureChecksOmittedAirAndNeverPublishesPartialOrCancelledCoverage() {
        Backend backend = new Backend(directory) {
            @Override public String read(BlockPosition pos) {
                if (pos.x() == 1) throw new BuilderException("chunk_unavailable","unloaded air is not observed air");
                return super.read(pos);
            }
        };
        BuilderSession session = session(backend);
        assertThrows(BuilderException.class,() -> session.readRegion("{\"minX\":0,\"minY\":1,\"minZ\":0,\"maxX\":2,\"maxY\":1,\"maxZ\":0,\"omitAir\":true}"));
        assertTrue(invocation.evidence.isEmpty());
    }

    @Test void cooperativeWriteYieldKeepsOneIntentPerPositionAndExactUndoImages() throws Exception {
        Backend backend = new Backend(directory) { @Override public long sliceDeadline() { return 0; } };
        BuilderSession session = session(backend);
        session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":1,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":2,\"y\":1,\"z\":0,\"state\":"+STONE+"}]");
        assertEquals(6,backend.callCount,"Each timed slice makes progress without native block count caps");
        long intents = 0, withdrawals = 0;
        try (java.util.stream.Stream<Path> paths = Files.list(directory.resolve("journals"))) {
            for (Path path : paths.collect(java.util.stream.Collectors.toList())) {
                JsonObject record = JsonParser.parseString(FixtureValues.readString(path)).getAsJsonObject();
                if (record.has("intents")) intents += record.getAsJsonArray("intents").size();
                if (record.has("unstarted")) withdrawals += record.getAsJsonArray("unstarted").size();
            }
        }
        assertEquals(3,intents,"Time-budget yield never republishes a durable intent tail");
        assertEquals(0,withdrawals,"Only actual failures can withdraw known-unstarted tail");
        session.finish();
        OperationJournal.Snapshot original = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(3,original.entries().size());
        assertTrue(original.entries().stream().noneMatch(OperationJournal.Entry::pending));
        session.undo(original.id());
        assertTrue(backend.blocks.values().stream().allMatch(AIR::equals));
    }

    @Test void cancellationBetweenTimedApplySlicesAbortsOnlyRemainingUnstartedIntents() throws Exception {
        Backend backend = new Backend(directory) {
            @Override public long sliceDeadline() { return 0; }
            @Override public <T>T call(Callable<T> action) {
                if (callCount == 4) throw new BuilderException("session_closed","cancelled before next owner slice");
                return super.call(action);
            }
        };
        BuilderSession session = session(backend);
        assertThrows(BuilderException.class,() -> session.writeRegion("[{\"x\":0,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":1,\"y\":1,\"z\":0,\"state\":"+STONE+"},{\"x\":2,\"y\":1,\"z\":0,\"state\":"+STONE+"}]"));
        OperationJournal.Snapshot original = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(1,original.entries().size());
        assertEquals(BlockSpec.fromJson(STONE),original.entries().get(0).verified());
        assertEquals(1,backend.writeCount);
        assertEquals(AIR,backend.read(new BlockPosition(1,1,0)));
    }

    @Test void connectionPhasesRespectCooperativeSlicesWithoutSkippingHaloPhysics() {
        Backend backend = new Backend(directory) { @Override public long sliceDeadline() { return 0; } };
        BuilderSession session = session(backend);
        session.syncPhysics(BOUNDS);
        assertEquals(27,backend.notifyCount,"Every explicit requested halo cell still receives native physics");
        assertEquals(54,backend.callCount,"One capture and one compare/notify/readback slice per cell");
        assertEquals(0,backend.writeCount);
    }

    @Test void cooperativeNotificationRecapturesItsOwnPhysicsChangedTailWithoutFalseConflict() {
        Backend backend = new Backend(directory) {
            @Override public long sliceDeadline() { return 0; }
            @Override public void notifyNeighbours(BlockPosition pos) {
                super.notifyNeighbours(pos);
                if (notifyCount == 1) blocks.put(new BlockPosition(0,0,-1),DIRT);
            }
        };
        BuilderSession session = session(backend);
        assertDoesNotThrow(() -> session.syncPhysics(BOUNDS));
        assertEquals(27,backend.notifyCount);
        assertEquals(DIRT,backend.read(new BlockPosition(0,0,-1)));
    }

    @Test void ordinaryConnectionRepairDoesNotBroadcastWholeVolumePhysics() throws Exception {
        Backend backend=new Backend(directory);backend.repair=STONE;
        BuilderSession session=session(backend);
        JsonObject result=JsonParser.parseString(session.updateConnections(BOUNDS)).getAsJsonObject();
        assertEquals("connection-shapes",result.get("phase").getAsString());
        assertEquals(1,backend.writeCount);assertEquals(0,backend.notifyCount);
        assertEquals(STONE,backend.read(new BlockPosition(0,1,0)));
        session.finish();
        OperationJournal.Snapshot journal = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(1,journal.entries().size());
        assertEquals(BlockSpec.fromJson(AIR),journal.entries().get(0).before());
        assertEquals(BlockSpec.fromJson(STONE),journal.entries().get(0).verified());
        ExtensionEvidence shape = FixtureValues.last(invocation.evidence);
        assertEquals("native-shapes;explicit-writes-only;no-physics-broadcast",shape.details().get("openallay_builder:coverage"));
    }

    @Test void bulkSparsePositionsPreserveOrderDuplicatesFullEntitiesAndFreshCapture() {
        Backend backend=new Backend(directory);
        String chest="{\"id\":\"minecraft:chest\",\"properties\":{},\"blockEntity\":\"{Items:[]}\"}";
        backend.blocks.put(new BlockPosition(-1,1,2),chest);
        BuilderSession session=session(backend);
        String input="[{\"x\":-1,\"y\":1,\"z\":2},{\"x\":5,\"y\":1,\"z\":2},{\"x\":-1,\"y\":1,\"z\":2}]";
        com.google.gson.JsonArray result = JsonParser.parseString(session.readPositions(input)).getAsJsonArray();
        assertEquals(3,result.size());assertEquals(1,backend.callCount);
        assertEquals(chest,result.get(0).getAsJsonObject().get("state").toString());
        assertEquals(AIR,result.get(1).getAsJsonObject().get("state").toString());
        assertEquals(result.get(0),result.get(2));
        backend.blocks.put(new BlockPosition(-1,1,2),DIRT);
        com.google.gson.JsonArray fresh = JsonParser.parseString(session.readPositions(input)).getAsJsonArray();
        assertEquals(DIRT,fresh.get(0).getAsJsonObject().get("state").toString());
        assertEquals("multi-slice-non-atomic",FixtureValues.last(invocation.evidence).details().get("openallay_builder:consistency"));
    }

    @Test void invalidOrUnloadedBulkPositionNeverPublishesCompletePartialResult() {
        Backend backend=new Backend(directory) {
            @Override public String read(BlockPosition pos){if(pos.x()==5)throw new BuilderException("chunk_unavailable","unloaded");return super.read(pos);}
        };
        BuilderSession session=session(backend);
        assertThrows(BuilderException.class,()->session.readPositions("[{\"x\":0,\"y\":1,\"z\":0},{\"x\":5,\"y\":1,\"z\":0}]"));
        assertTrue(invocation.evidence.isEmpty());
        int calls=backend.callCount;
        assertThrows(BuilderException.class,()->session.readPositions("[{\"x\":0,\"y\":1,\"z\":0},{\"x\":1.5,\"y\":1,\"z\":0}]"));
        assertEquals(calls,backend.callCount,"All coordinate parsing completes before native work");
    }

    static class Invocation implements SessionInvocation {
        boolean cancelled,success;
        List<ExtensionEvidence> evidence=new ArrayList<>();
        public boolean cancelled(){return cancelled;}
        public boolean completedSuccessfully(){return success;}
        public void evidence(ExtensionEvidence item){evidence.add(item);}
        public String loader(){return "fabric";}
        public String gameVersion(){return "1.20.1";}
    }
    static class Backend implements BuilderBackend {
        final Path path;
        Map<BlockPosition,String> blocks=new HashMap<>();
        String repair;
        boolean raceDuringRepair,failPreview,failAfterWrite,notifyChanges,raceBeforeApply;
        int readCount;
        int raceOnRead=-1;
        int writeCount,notifyCount,callCount;
        int raceOnCall=-1;
        BlockPosition racePosition;
        Backend(Path path){this.path=path;}
        public <T>T call(Callable<T> action){callCount++;if(callCount==raceOnCall)blocks.put(racePosition,DIRT);try{return action.call();}catch(RuntimeException failure){throw failure;}catch(Exception failure){throw new RuntimeException(failure);}}
        public void validatePosition(BlockPosition pos){}
        public String read(BlockPosition pos){readCount++;if((raceBeforeApply && readCount==2)||readCount==raceOnRead)blocks.put(pos,STONE);return blocks.getOrDefault(pos,AIR);}
        public String preview(BlockPosition pos,String state){if(failPreview)throw new BuilderException("invalid_state","bad preview");return state;}
        public WriteOutcome write(BlockPosition pos,String state){writeCount++;blocks.put(pos,failAfterWrite?DIRT:state);return new WriteOutcome(read(pos),true,failAfterWrite?new BuilderException("placement_failed","native hook changed target"):null);}
        public String transform(String state,int degrees,String mirror){return state;}
        public String repairedState(BlockPosition pos){if(pos.equals(new BlockPosition(0,1,0))){if(raceDuringRepair)blocks.put(pos,DIRT);return repair;}return null;}
        public void notifyNeighbours(BlockPosition pos){
            notifyCount++;
            if(notifyChanges) for(int x=-1;x<=1;x++)for(int y=0;y<=2;y++)for(int z=-1;z<=1;z++)blocks.put(new BlockPosition(x,y,z),DIRT);
        }
        public String context(){return "{\"minY\":-64,\"maxY\":320}";}
        public String dimension(){return "minecraft:overworld";}
        public String worldId(){return "test-world";}
        public java.util.Optional<String> existingWorldId(){return java.util.Optional.of("test-world");}
        public Path artifacts(){return path;}
    }
}
