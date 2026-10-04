package dev.openallay.builder.storage;

import static org.junit.jupiter.api.Assertions.*;
import dev.openallay.builder.FixtureValues;

import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OperationJournalTest {
    @TempDir Path directory;
    private static final BlockPosition A = new BlockPosition(-20, -64, 5);
    private static final BlockPosition B = new BlockPosition(10, 300, 10);
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", FixtureValues.map());
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", FixtureValues.map());
    private static final BlockSpec CHEST = new BlockSpec("minecraft:chest", FixtureValues.map("facing", "west"),
            "{Items:[{Slot:0b,id:'minecraft:diamond',count:2}]}");

    @Test void durableIntentPrecedesVerificationAndPreservesFirstTouchOrder() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world-id", "minecraft:overworld", "house");
        operation.recordIntent(A, AIR, STONE);
        OperationJournal.Snapshot pending = journal.load(operation.id());
        assertEquals(OperationJournal.Status.RUNNING, pending.status());
        assertEquals(AIR, pending.entries().get(0).before());
        assertEquals(STONE, pending.entries().get(0).intended());
        assertNull(pending.entries().get(0).verified());
        assertEquals(STONE, pending.entries().get(0).expected());
        operation.verified(A, STONE);
        operation.recordIntent(B, AIR, CHEST);
        operation.verified(B, CHEST);
        operation.recordIntent(A, STONE, CHEST);
        operation.verified(A, CHEST);
        operation.finish(OperationJournal.Status.COMPLETED, "verified");
        OperationJournal.Snapshot done = journal.load(operation.id());
        assertEquals(FixtureValues.list(A, B), done.entries().stream().map(OperationJournal.Entry::position).collect(java.util.stream.Collectors.toList()));
        assertEquals(FixtureValues.list(B, A), done.reverseEntries().stream().map(OperationJournal.Entry::position).collect(java.util.stream.Collectors.toList()));
        assertEquals(FixtureValues.list(0L, 1L), done.entries().stream().map(OperationJournal.Entry::sequence).collect(java.util.stream.Collectors.toList()));
        assertEquals(AIR, done.entries().get(0).before());
        assertEquals(CHEST, done.entries().get(0).verified());
        assertEquals(OperationJournal.Status.COMPLETED, done.status());
        assertEquals("verified", done.detail());
        assertThrows(UnsupportedOperationException.class, () -> done.entries().clear());
        assertThrows(UnsupportedOperationException.class, () -> done.reverseEntries().clear());
        assertThrows(IllegalStateException.class, () -> operation.recordIntent(A, CHEST, AIR));
        assertThrows(IllegalStateException.class, () -> operation.finish(OperationJournal.Status.FAILED, "late"));
        assertEquals(done, new OperationJournal(directory).load(operation.id()));
    }

    @Test void startupMarksPendingAndVerifiedRunningOperationsInterruptedWithoutReplay() throws Exception {
        OperationJournal firstProcess = new OperationJournal(directory);
        OperationJournal.Operation pending = firstProcess.begin("world", "minecraft:overworld", "pending");
        pending.recordIntent(A, AIR, CHEST);
        OperationJournal.Operation verified = firstProcess.begin("world", "minecraft:overworld", "applied");
        verified.recordIntent(B, AIR, STONE);
        verified.verified(B, STONE);
        OperationJournal.Operation cancelled = firstProcess.begin("world", "minecraft:overworld", "cancel");
        cancelled.finish(OperationJournal.Status.CANCELLED, "cancelled");
        assertTrue(firstProcess.recoverInterrupted().isEmpty(), "Do not interrupt this instance's active operations");
        OperationJournal afterRestart = new OperationJournal(directory);
        assertEquals(OperationJournal.Status.INTERRUPTED, afterRestart.load(pending.id()).status());
        assertTrue(afterRestart.load(pending.id()).entries().get(0).pending());
        assertEquals(CHEST, afterRestart.load(pending.id()).entries().get(0).expected());
        assertEquals(OperationJournal.Status.INTERRUPTED, afterRestart.load(verified.id()).status());
        assertEquals(STONE, afterRestart.load(verified.id()).entries().get(0).verified());
        assertEquals(OperationJournal.Status.CANCELLED, afterRestart.load(cancelled.id()).status());
        assertTrue(afterRestart.recoverInterrupted().isEmpty());
        assertEquals(3, afterRestart.list().size());
    }

    @Test void interruptedRetouchUsesLatestIntentAndKeepsOriginalBeforeImage() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "retouch");
        operation.recordIntent(A, AIR, STONE);
        operation.verified(A, STONE);
        operation.recordIntent(A, STONE, CHEST);
        OperationJournal.Snapshot recovered = new OperationJournal(directory).load(operation.id());
        OperationJournal.Entry entry = recovered.entries().get(0);
        assertEquals(OperationJournal.Status.INTERRUPTED, recovered.status());
        assertEquals(AIR, entry.before());
        assertEquals(CHEST, entry.expected());
        assertNull(entry.verified());
        assertTrue(entry.check(CHEST).matches());
        assertFalse(entry.check(STONE).matches());
        assertEquals(0, entry.sequence());
    }

    @Test void undoChecksBlockStateAndFullBlockEntityAndReportsExplicitConflicts() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:the_nether", "undo-source");
        operation.recordIntent(A, AIR, CHEST);
        operation.verified(A, CHEST);
        operation.finish(OperationJournal.Status.COMPLETED, "");
        OperationJournal.Entry entry = journal.load(operation.id()).reverseEntries().get(0);
        assertTrue(entry.check(CHEST).matches());
        BlockSpec external = new BlockSpec(CHEST.id(), CHEST.properties(), "{Items:[]}");
        OperationJournal.UndoCheck conflict = entry.check(external);
        assertFalse(conflict.matches());
        assertEquals(CHEST, conflict.expected());
        assertEquals(external, conflict.current());
        assertFalse(entry.check(STONE).matches());
        assertEquals(AIR, entry.before());
        assertThrows(NullPointerException.class, () -> entry.check(null));
    }

    @Test void equalityToPendingIntendedIsDiagnosticAndNeverMakesDefaultUndoEligible() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "uncertain");
        operation.recordIntent(A, AIR, STONE);
        OperationJournal.Entry pending = operation.snapshot().entries().get(0);
        assertTrue(pending.check(STONE).matches());
        assertTrue(pending.pending());
        assertFalse(pending.undoEligible());
        OperationJournal.Entry afterRestart = new OperationJournal(directory).load(operation.id()).entries().get(0);
        assertTrue(afterRestart.check(STONE).matches());
        assertFalse(afterRestart.undoEligible());
        // In a separate live operation, authoritative readback establishes eligibility.
        OperationJournal.Entry verified = new OperationJournal.Entry(A, AIR, STONE, STONE, 0);
        assertTrue(verified.undoEligible());
        assertFalse(verified.pending());
    }

    @Test void failedOrCancelledPartialOperationKeepsVerifiedActualNotAssumedIntended() throws Exception {
        for (OperationJournal.Status status : FixtureValues.list(OperationJournal.Status.FAILED, OperationJournal.Status.CANCELLED)) {
            OperationJournal journal = new OperationJournal(directory.resolve(status.name()));
            OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "partial");
            operation.recordIntent(A, AIR, CHEST);
            operation.verified(A, STONE);
            operation.recordIntent(B, AIR, CHEST);
            operation.finish(status, "partial result");
            OperationJournal.Snapshot snapshot = journal.load(operation.id());
            assertEquals(status, snapshot.status());
            assertEquals(STONE, snapshot.entries().get(0).expected());
            assertEquals(CHEST, FixtureValues.last(snapshot.entries()).expected());
            assertTrue(FixtureValues.last(snapshot.entries()).pending());
        }
    }

    @Test void cannotCompleteWithoutReadbackOrReplacePendingOrStaleImage() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "checks");
        assertThrows(IllegalStateException.class, () -> operation.verified(A, STONE));
        operation.recordIntent(A, AIR, STONE);
        assertThrows(IllegalArgumentException.class, () -> operation.finish(OperationJournal.Status.COMPLETED, "bad"));
        assertThrows(IllegalStateException.class, () -> operation.recordIntent(A, STONE, CHEST));
        operation.verified(A, STONE);
        assertThrows(IllegalStateException.class, () -> operation.verified(A, CHEST));
        assertThrows(IllegalStateException.class, () -> operation.recordIntent(A, AIR, CHEST));
        operation.recordIntent(A, STONE, CHEST);
        OperationJournal.Entry pending = operation.snapshot().entries().get(0);
        assertTrue(pending.pending());
        assertEquals(CHEST, pending.expected(), "Latest intent, not a stale previous verified image");
    }

    @Test void batchesPublishOnceAndRejectInvalidBatchWithoutPublishingPartialState() throws Exception {
        java.util.concurrent.atomic.AtomicInteger publications = new java.util.concurrent.atomic.AtomicInteger();
        OperationJournal journal = new OperationJournal(directory, (temporary, destination) -> {
            publications.incrementAndGet();
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        }, Clock.systemUTC());
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "batch");
        assertEquals(1, publications.get());
        operation.recordIntents(FixtureValues.list(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST)));
        assertEquals(2, publications.get());
        assertEquals(2, journal.load(operation.id()).entries().size());
        operation.verifiedAll(FixtureValues.map(A, STONE, B, CHEST));
        assertEquals(3, publications.get());
        OperationJournal.Snapshot unchanged = operation.snapshot();
        assertThrows(IllegalArgumentException.class, () -> operation.recordIntents(FixtureValues.list(
                new OperationJournal.Intent(A, STONE, AIR), new OperationJournal.Intent(A, STONE, CHEST))));
        assertEquals(unchanged, operation.snapshot());
        assertEquals(3, publications.get());
        operation.recordIntents(FixtureValues.list(new OperationJournal.Intent(A, STONE, AIR),
                new OperationJournal.Intent(B, CHEST, STONE)));
        OperationJournal.Snapshot pending = operation.snapshot();
        assertThrows(IllegalStateException.class, () -> operation.verifiedAll(FixtureValues.map(A, AIR,
                new BlockPosition(99, 99, 99), AIR)));
        assertEquals(pending, operation.snapshot());
        assertEquals(4, publications.get());
        operation.verifiedAll(FixtureValues.map(A, AIR, B, STONE));
        assertEquals(AIR, operation.snapshot().entries().get(0).before());
        assertEquals(FixtureValues.list(A, B), operation.snapshot().entries().stream().map(OperationJournal.Entry::position).collect(java.util.stream.Collectors.toList()));
        assertEquals(5, publications.get());
    }

    @Test void knownUnappliedIntentCannotUndoAnExternalEditThatEqualsItsIntendedImage() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "compare-race");
        operation.recordIntents(FixtureValues.list(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST)));
        // Native CAS saw an external AIR -> STONE edit before its own first mutation. Neither write began.
        operation.abortIntents(FixtureValues.list(A, B));
        operation.finish(OperationJournal.Status.FAILED, "before-image conflict");
        OperationJournal.Snapshot failed = journal.load(operation.id());
        assertTrue(failed.entries().isEmpty());
        assertTrue(failed.reverseEntries().stream().noneMatch(entry -> entry.check(STONE).matches()));
        assertEquals(failed, new OperationJournal(directory).load(operation.id()));
    }

    @Test void abortingRetouchRestoresPreviousVerifiedImageAndOriginalOrder() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "retouch-abort");
        operation.recordIntents(FixtureValues.list(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST)));
        operation.verifiedAll(FixtureValues.map(A, STONE, B, CHEST));
        List<OperationJournal.Entry> beforeRetouch = operation.snapshot().entries();
        operation.recordIntent(A, STONE, CHEST);
        OperationJournal.Entry pending = journal.load(operation.id()).entries().get(0);
        assertEquals(STONE, pending.previousIntended());
        assertEquals(STONE, pending.previousVerified());
        operation.abortIntents(FixtureValues.list(A));
        assertEquals(beforeRetouch, operation.snapshot().entries());
        assertEquals(beforeRetouch, journal.load(operation.id()).entries());
        assertTrue(operation.snapshot().entries().get(0).check(STONE).matches());
        assertFalse(operation.snapshot().entries().get(0).check(CHEST).matches());
        assertThrows(IllegalStateException.class, () -> operation.abortIntents(FixtureValues.list(A)));
    }

    @Test void abortKeepsUncertainStartedEntryAndPreservesRemainingFirstTouchSequence() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "partial-abort");
        BlockPosition c = new BlockPosition(15, 42, 19);
        operation.recordIntents(FixtureValues.list(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST), new OperationJournal.Intent(c, AIR, STONE)));
        // B may have begun and is deliberately left ambiguous; A and C are known unstarted.
        operation.abortIntents(FixtureValues.list(A, c));
        OperationJournal.Entry entry = operation.snapshot().entries().get(0);
        assertEquals(B, entry.position());
        assertEquals(1, entry.sequence());
        assertTrue(entry.pending());
        assertEquals(CHEST, entry.expected());
        operation.recordIntent(c, AIR, STONE);
        assertEquals(FixtureValues.list(1L, 2L), operation.snapshot().entries().stream().map(OperationJournal.Entry::sequence).collect(java.util.stream.Collectors.toList()));
        assertEquals(operation.snapshot(), journal.load(operation.id()));
    }

    @Test void abortPublicationFailureDoesNotErasePendingIntent() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        OperationJournal journal = new OperationJournal(directory, (temporary, destination) -> {
            if (fail.get()) throw new IOException("disk error");
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        }, Clock.systemUTC());
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "abort-failure");
        operation.recordIntent(A, AIR, STONE);
        OperationJournal.Snapshot pending = operation.snapshot();
        fail.set(true);
        assertThrows(IOException.class, () -> operation.abortIntents(FixtureValues.list(A)));
        assertEquals(pending, operation.snapshot());
        assertEquals(pending, journal.load(operation.id()));
    }

    @Test void failedIntentPublicationDoesNotPublishInMemoryOrReplacePriorSnapshot() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        OperationJournal journal = new OperationJournal(directory, (temporary, destination) -> {
            if (fail.get()) throw new IOException("disk full before rename");
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        }, Clock.systemUTC());
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "failure");
        OperationJournal.Snapshot before = operation.snapshot();
        fail.set(true);
        assertThrows(IOException.class, () -> operation.recordIntent(A, AIR, STONE));
        assertEquals(before, operation.snapshot());
        assertEquals(before, journal.load(operation.id()));
        fail.set(false);
        assertThrows(IOException.class, () -> operation.recordIntent(A,AIR,STONE),"Failed publication poisons admission; never overwrite an uncertain delta");
        OperationJournal.Operation second = journal.begin("world","minecraft:overworld","verification failure");
        second.recordIntent(A,AIR,STONE);
        OperationJournal.Snapshot pending = second.snapshot();
        fail.set(true);
        assertThrows(IOException.class, () -> second.verified(A,STONE));
        assertEquals(pending,second.snapshot());
        assertEquals(pending,journal.load(second.id()));
        assertThrows(IOException.class, () -> second.finish(OperationJournal.Status.FAILED,"disk failure"));
        assertEquals(OperationJournal.Status.RUNNING,second.snapshot().status());
        try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(3,files.count(),"Two bases plus durable intent delta"); }
    }

    @Test void unknownFieldsCorruptAndMalformedJournalsAreRejectedWithoutDeletingContent() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "valid");
        operation.recordIntent(A, AIR, STONE);
        JsonObject original = OperationJournal.encode(operation.snapshot());
        Path file = directory.resolve(operation.id() + ".json");
        JsonObject future = original.deepCopy(); future.addProperty("unknownField", 3);
        FixtureValues.writeString(file, future.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        assertThrows(IOException.class, () -> new OperationJournal(directory));
        assertEquals(future.toString(), FixtureValues.readString(file));
        JsonObject badOrder = original.deepCopy();
        badOrder.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("sequence", -1);
        FixtureValues.writeString(file, badOrder.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        JsonObject duplicate = original.deepCopy(); duplicate.getAsJsonArray("entries").add(duplicate.getAsJsonArray("entries").get(0));
        FixtureValues.writeString(file, duplicate.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        FixtureValues.writeString(file, "{not-json");
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        assertThrows(IllegalArgumentException.class, () -> journal.load("../../outside"));
    }

    @Test void linearDeltaBytesAndExactlyTwoDurablePublicationsPerQuantum() throws Exception {
        java.util.concurrent.atomic.AtomicInteger publications = new java.util.concurrent.atomic.AtomicInteger();
        java.util.concurrent.atomic.AtomicLong bytes = new java.util.concurrent.atomic.AtomicLong();
        OperationJournal journal = new OperationJournal(directory,(temporary,destination)-> {
            publications.incrementAndGet();bytes.addAndGet(Files.size(temporary));
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary,destination);
        },Clock.systemUTC());
        OperationJournal.Operation operation = journal.begin("world","minecraft:overworld","large fill");
        long firstQuantumBytes=0;
        for(int slice=0;slice<12;slice++) {
            List<OperationJournal.Intent> intents=new java.util.ArrayList<>();
            Map<BlockPosition,BlockSpec> actual=new java.util.LinkedHashMap<>();
            for(int i=0;i<256;i++) {BlockPosition position = new BlockPosition(slice*256+i,64,0);intents.add(new OperationJournal.Intent(position,AIR,STONE));actual.put(position,STONE);}
            long before=bytes.get();operation.recordIntents(intents);operation.resolve(actual,FixtureValues.list());
            long quantumBytes=bytes.get()-before;
            if(slice==0)firstQuantumBytes=quantumBytes;
            assertTrue(quantumBytes<firstQuantumBytes*1.2,"Later quanta must not serialize prior entries");
        }
        assertEquals(25,publications.get()); // base + 12(intent + outcome)
        assertEquals(3072,operation.snapshot().entries().size());
        assertEquals(operation.snapshot(),journal.load(operation.id()));
        operation.finish(OperationJournal.Status.COMPLETED,"all verified");
        assertEquals(26,publications.get());
        try(java.util.stream.Stream<Path> files = Files.list(directory)){assertEquals(1,files.count(),"Completed compact snapshot, no remaining deltas");}
        assertEquals(OperationJournal.Status.COMPLETED,journal.load(operation.id()).status());
    }

    @Test void crashCutsKeepNoIntentPendingAndVerifiedImagesDistinctWithoutWorldReplay() throws Exception {
        OperationJournal emptyJournal = new OperationJournal(directory.resolve("before-intent"));
        OperationJournal.Operation empty = emptyJournal.begin("world","minecraft:overworld","empty");
        assertTrue(new OperationJournal(directory.resolve("before-intent")).load(empty.id()).entries().isEmpty());
        OperationJournal intentJournal = new OperationJournal(directory.resolve("after-intent"));
        OperationJournal.Operation pending = intentJournal.begin("world","minecraft:overworld","admitted");
        pending.recordIntents(FixtureValues.list(new OperationJournal.Intent(A,AIR,STONE),new OperationJournal.Intent(B,AIR,CHEST)));
        // Same disk cut covers after-intent/before-apply AND mid-apply before detached readback.
        OperationJournal.Snapshot interrupted = new OperationJournal(directory.resolve("after-intent")).load(pending.id());
        assertEquals(OperationJournal.Status.INTERRUPTED,interrupted.status());
        assertTrue(interrupted.entries().stream().allMatch(OperationJournal.Entry::pending));
        assertTrue(interrupted.entries().stream().noneMatch(OperationJournal.Entry::undoEligible));
        OperationJournal outcomeJournal = new OperationJournal(directory.resolve("after-outcome"));
        OperationJournal.Operation actual = outcomeJournal.begin("world","minecraft:overworld","partial");
        actual.recordIntents(FixtureValues.list(new OperationJournal.Intent(A,AIR,CHEST),new OperationJournal.Intent(B,AIR,STONE)));
        actual.resolve(FixtureValues.map(A,STONE),FixtureValues.list(B));
        actual.recordIntent(B,AIR,CHEST);
        OperationJournal.Snapshot recovered = new OperationJournal(directory.resolve("after-outcome")).load(actual.id());
        assertEquals(STONE,recovered.entries().get(0).verified(),"Actual, not intended, survived outcome cut");
        assertTrue(FixtureValues.last(recovered.entries()).pending(),"Next admitted quantum remains uncertain");
    }

    @Test void missingCorruptAndMismatchedDeltaSequenceFailsWithoutRepairingArtifacts() throws Exception {
        OperationJournal journal = new OperationJournal(directory);OperationJournal.Operation operation = journal.begin("world","minecraft:overworld","gap");
        operation.recordIntent(A,AIR,STONE);operation.verified(A,STONE);operation.recordIntent(B,AIR,CHEST);
        Path first=directory.resolve(operation.id()+"--00000000000000000001.json");
        byte[] original=Files.readAllBytes(first);Files.delete(first);
        assertThrows(IOException.class,()->journal.load(operation.id()));
        assertThrows(IOException.class,()->new OperationJournal(directory));
        Files.write(first,original);
        FixtureValues.writeString(first,"{broken");assertThrows(IOException.class,()->journal.load(operation.id()));
        Files.write(first,original);
        JsonObject mismatched=StrictJson.parse(new String(original,java.nio.charset.StandardCharsets.UTF_8));mismatched.addProperty("sequence",2);
        FixtureValues.writeString(first,mismatched.toString());assertThrows(IOException.class,()->journal.load(operation.id()));
        assertEquals(mismatched.toString(),FixtureValues.readString(first),"Failure does not rewrite corrupt records");
    }

    @Test void compactionCutsRetainDeltasUntilCheckpointPublishAndIgnoreCoveredSegmentsAfterPublish() throws Exception {
        AtomicBoolean beforeRename=new AtomicBoolean(),afterRename=new AtomicBoolean();
        OperationJournal journal = new OperationJournal(directory,(temporary,destination)-> {
            if(beforeRename.get() && !destination.getFileName().toString().contains("--"))throw new IOException("checkpoint before rename");
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary,destination);
            if(afterRename.get() && !destination.getFileName().toString().contains("--"))throw new IOException("checkpoint after rename before cleanup");
        },Clock.systemUTC());
        OperationJournal.Operation operation = journal.begin("world","minecraft:overworld","compact");operation.recordIntent(A,AIR,STONE);operation.verified(A,STONE);
        beforeRename.set(true);assertThrows(IOException.class,()->operation.finish(OperationJournal.Status.COMPLETED,"done"));
        assertEquals(OperationJournal.Status.RUNNING,journal.load(operation.id()).status());
        try(java.util.stream.Stream<Path> files = Files.list(directory)){assertEquals(3,files.count());}
        beforeRename.set(false);
        assertThrows(IOException.class,()->operation.recordIntent(B,AIR,CHEST),"Uncertain checkpoint publication poisons the old handle even when rename did not occur");
        // Independently model a checkpoint that renamed but failed before cleanup.
        Path secondDirectory=directory.resolve("after-checkpoint-rename");
        OperationJournal secondJournal = new OperationJournal(secondDirectory,(temporary,destination)-> {
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary,destination);
            if(afterRename.get() && !destination.getFileName().toString().contains("--"))throw new IOException("checkpoint after rename before cleanup");
        },Clock.systemUTC());
        OperationJournal.Operation second = secondJournal.begin("world","minecraft:overworld","compact after rename");second.recordIntent(A,AIR,STONE);second.verified(A,STONE);
        afterRename.set(true);assertThrows(IOException.class,()->second.finish(OperationJournal.Status.COMPLETED,"done"));
        OperationJournal.Snapshot compacted = secondJournal.load(second.id());assertEquals(OperationJournal.Status.COMPLETED,compacted.status());assertEquals(STONE,compacted.entries().get(0).verified());
        afterRename.set(false);assertThrows(IOException.class,()->second.recordIntent(B,AIR,CHEST),"Old handle must never add deltas after uncertain terminal publication");
        assertThrows(IOException.class,()->second.finish(OperationJournal.Status.FAILED,"cannot relabel terminal disk image"));
        try(java.util.stream.Stream<Path> files = Files.list(secondDirectory)){assertEquals(3,files.count(),"Covered deltas may survive cleanup crash");}
        assertEquals(compacted,new OperationJournal(secondDirectory).load(second.id()));
    }

    @Test void deltaPublicationFailureAfterRenameCannotBeOverwrittenOrCompactedFromStaleMemory() throws Exception {
        AtomicBoolean afterRename=new AtomicBoolean();
        OperationJournal journal = new OperationJournal(directory,(temporary,destination)-> {
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary,destination);
            if(afterRename.get() && destination.getFileName().toString().contains("--"))throw new IOException("after delta rename before directory force");
        },Clock.systemUTC());
        OperationJournal.Operation operation = journal.begin("world","minecraft:overworld","uncertain publish");
        afterRename.set(true);assertThrows(IOException.class,()->operation.recordIntent(A,AIR,STONE));
        assertTrue(operation.snapshot().entries().isEmpty(),"Memory admission requires successful durable publication");
        OperationJournal.Snapshot durable = journal.load(operation.id());assertTrue(durable.entries().get(0).pending());
        afterRename.set(false);assertThrows(IOException.class,()->operation.recordIntent(B,AIR,CHEST));
        assertThrows(IOException.class,()->operation.finish(OperationJournal.Status.FAILED,"cannot compact stale memory"));
        assertEquals(durable,journal.load(operation.id()));
        OperationJournal.Snapshot recovered = new OperationJournal(directory).load(operation.id());
        assertEquals(OperationJournal.Status.INTERRUPTED,recovered.status());assertTrue(recovered.entries().get(0).pending());
    }

    @Test void malformedCurrentJournalIsRejectedWithoutMigrationDeletionOrReplay() throws Exception {
        String id=java.util.UUID.randomUUID().toString();Path file=directory.resolve(id+".json");
        String content="{\"format\":\"openallay:operation-journal\",\"id\":\""+id+"\"}";
        FixtureValues.writeString(file,content);
        assertThrows(IOException.class,()->new OperationJournal(directory));
        assertEquals(content,FixtureValues.readString(file));
    }

    @Test void operationIdsMustMatchTheirFileAndNoArbitraryWorldPathIsOpened() throws Exception {
        OperationJournal journal = new OperationJournal(directory.resolve("app-journals"));
        Path nonexistentWorld = directory.resolve("world-that-is-never-opened");
        OperationJournal.Operation operation = journal.begin(nonexistentWorld.toString(), "example:dimension", "path-as-identity-only");
        assertFalse(Files.exists(nonexistentWorld));
        JsonObject value = OperationJournal.encode(operation.snapshot());
        value.addProperty("id", java.util.UUID.randomUUID().toString());
        FixtureValues.writeString(directory.resolve("app-journals").resolve(operation.id() + ".json"), value.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
    }
    @Test void streamedCheckpointKeepsExactCurrentShapeIncludingRetouchHistory() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world-中文", "minecraft:overworld", "\"quoted\" <tag> \ud83d\udc9a");
        BlockSpec special = new BlockSpec("minecraft:chest", FixtureValues.map("facing", "west"), "{CustomName:'\"中文\"',Items:[]}");
        operation.recordIntents(FixtureValues.list(new OperationJournal.Intent(A, AIR, special), new OperationJournal.Intent(B, AIR, STONE)));
        operation.verifiedAll(FixtureValues.map(A, special, B, STONE));
        operation.recordIntent(A, special, STONE);
        operation.finish(OperationJournal.Status.FAILED, "pending retouch\n<diagnostic>");
        OperationJournal.Snapshot snapshot = operation.snapshot();
        assertEquals(OperationJournal.encode(snapshot), StrictJson.parse(FixtureValues.readString(directory.resolve(operation.id() + ".json"))));
        assertEquals(snapshot, journal.load(operation.id()));
        assertEquals(special, snapshot.entries().get(0).previousVerified());
        assertEquals(snapshot, new OperationJournal(directory).load(operation.id()));
    }

    @Test void withdrawalsReuseOnlyRemovedTrailingFirstTouchSequence() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "tail order");
        BlockPosition c = new BlockPosition(20, 64, 0);
        BlockPosition d = new BlockPosition(21, 64, 0);
        BlockPosition e = new BlockPosition(22, 64, 0);
        operation.recordIntents(FixtureValues.list(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, STONE), new OperationJournal.Intent(c, AIR, STONE)));
        operation.abortIntents(FixtureValues.list(B));
        operation.recordIntent(d, AIR, STONE);
        assertEquals(FixtureValues.list(0L, 2L, 3L), operation.snapshot().entries().stream().map(OperationJournal.Entry::sequence).collect(java.util.stream.Collectors.toList()));
        operation.abortIntents(FixtureValues.list(c, d));
        operation.recordIntent(e, AIR, STONE);
        assertEquals(FixtureValues.list(0L, 1L), operation.snapshot().entries().stream().map(OperationJournal.Entry::sequence).collect(java.util.stream.Collectors.toList()));
        operation.verifiedAll(FixtureValues.map(A, STONE, e, STONE));
        operation.recordIntent(A, STONE, CHEST);
        operation.abortIntents(FixtureValues.list(A));
        operation.recordIntent(B, AIR, STONE);
        assertEquals(FixtureValues.list(0L, 1L, 2L), operation.snapshot().entries().stream().map(OperationJournal.Entry::sequence).collect(java.util.stream.Collectors.toList()));
        assertEquals(operation.snapshot(), journal.load(operation.id()));
    }

    @Test void liveFinishDoesNotRescanUnrelatedHistoryButListingStillRejectsCorruption() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        OperationJournal.Operation operation = journal.begin("world", "minecraft:overworld", "independent cleanup");
        operation.recordIntent(A, AIR, STONE);
        operation.verified(A, STONE);
        Path unrelated = directory.resolve("not-a-journal-id.json");
        String corrupt = "{broken unrelated history";
        FixtureValues.writeString(unrelated, corrupt);
        operation.finish(OperationJournal.Status.COMPLETED, "done");
        assertEquals(OperationJournal.Status.COMPLETED, operation.snapshot().status());
        assertFalse(Files.exists(directory.resolve(operation.id() + "--00000000000000000001.json")));
        assertFalse(Files.exists(directory.resolve(operation.id() + "--00000000000000000002.json")));
        assertEquals(corrupt, FixtureValues.readString(unrelated));
        assertThrows(IOException.class, journal::list, "Explicit history inspection must still reject corrupt records");
        assertThrows(IOException.class, () -> new OperationJournal(directory));
    }

}
