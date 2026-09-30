package dev.openallay.builder.storage;

import static org.junit.jupiter.api.Assertions.*;

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
    private static final BlockSpec AIR = new BlockSpec("minecraft:air", Map.of());
    private static final BlockSpec STONE = new BlockSpec("minecraft:stone", Map.of());
    private static final BlockSpec CHEST = new BlockSpec("minecraft:chest", Map.of("facing", "west"),
            "{Items:[{Slot:0b,id:'minecraft:diamond',count:2}]}");

    @Test void durableIntentPrecedesVerificationAndPreservesFirstTouchOrder() throws Exception {
        OperationJournal journal = new OperationJournal(directory);
        var operation = journal.begin("world-id", "minecraft:overworld", "house");
        operation.recordIntent(A, AIR, STONE);
        var pending = journal.load(operation.id());
        assertEquals(OperationJournal.Status.RUNNING, pending.status());
        assertEquals(AIR, pending.entries().getFirst().before());
        assertEquals(STONE, pending.entries().getFirst().intended());
        assertNull(pending.entries().getFirst().verified());
        assertEquals(STONE, pending.entries().getFirst().expected());
        operation.verified(A, STONE);
        operation.recordIntent(B, AIR, CHEST);
        operation.verified(B, CHEST);
        operation.recordIntent(A, STONE, CHEST);
        operation.verified(A, CHEST);
        operation.finish(OperationJournal.Status.COMPLETED, "verified");
        var done = journal.load(operation.id());
        assertEquals(List.of(A, B), done.entries().stream().map(OperationJournal.Entry::position).toList());
        assertEquals(List.of(B, A), done.reverseEntries().stream().map(OperationJournal.Entry::position).toList());
        assertEquals(List.of(0L, 1L), done.entries().stream().map(OperationJournal.Entry::sequence).toList());
        assertEquals(AIR, done.entries().getFirst().before());
        assertEquals(CHEST, done.entries().getFirst().verified());
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
        var pending = firstProcess.begin("world", "minecraft:overworld", "pending");
        pending.recordIntent(A, AIR, CHEST);
        var verified = firstProcess.begin("world", "minecraft:overworld", "applied");
        verified.recordIntent(B, AIR, STONE);
        verified.verified(B, STONE);
        var cancelled = firstProcess.begin("world", "minecraft:overworld", "cancel");
        cancelled.finish(OperationJournal.Status.CANCELLED, "cancelled");
        assertTrue(firstProcess.recoverInterrupted().isEmpty(), "Do not interrupt this instance's active operations");
        OperationJournal afterRestart = new OperationJournal(directory);
        assertEquals(OperationJournal.Status.INTERRUPTED, afterRestart.load(pending.id()).status());
        assertTrue(afterRestart.load(pending.id()).entries().getFirst().pending());
        assertEquals(CHEST, afterRestart.load(pending.id()).entries().getFirst().expected());
        assertEquals(OperationJournal.Status.INTERRUPTED, afterRestart.load(verified.id()).status());
        assertEquals(STONE, afterRestart.load(verified.id()).entries().getFirst().verified());
        assertEquals(OperationJournal.Status.CANCELLED, afterRestart.load(cancelled.id()).status());
        assertTrue(afterRestart.recoverInterrupted().isEmpty());
        assertEquals(3, afterRestart.list().size());
    }

    @Test void interruptedRetouchUsesLatestIntentAndKeepsOriginalBeforeImage() throws Exception {
        var journal = new OperationJournal(directory);
        var operation = journal.begin("world", "minecraft:overworld", "retouch");
        operation.recordIntent(A, AIR, STONE);
        operation.verified(A, STONE);
        operation.recordIntent(A, STONE, CHEST);
        var recovered = new OperationJournal(directory).load(operation.id());
        var entry = recovered.entries().getFirst();
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
        var operation = journal.begin("world", "minecraft:the_nether", "undo-source");
        operation.recordIntent(A, AIR, CHEST);
        operation.verified(A, CHEST);
        operation.finish(OperationJournal.Status.COMPLETED, "");
        var entry = journal.load(operation.id()).reverseEntries().getFirst();
        assertTrue(entry.check(CHEST).matches());
        var external = new BlockSpec(CHEST.id(), CHEST.properties(), "{Items:[]}");
        var conflict = entry.check(external);
        assertFalse(conflict.matches());
        assertEquals(CHEST, conflict.expected());
        assertEquals(external, conflict.current());
        assertFalse(entry.check(STONE).matches());
        assertEquals(AIR, entry.before());
        assertThrows(NullPointerException.class, () -> entry.check(null));
    }

    @Test void equalityToPendingIntendedIsDiagnosticAndNeverMakesDefaultUndoEligible() throws Exception {
        var journal = new OperationJournal(directory);
        var operation = journal.begin("world", "minecraft:overworld", "uncertain");
        operation.recordIntent(A, AIR, STONE);
        var pending = operation.snapshot().entries().getFirst();
        assertTrue(pending.check(STONE).matches());
        assertTrue(pending.pending());
        assertFalse(pending.undoEligible());
        var afterRestart = new OperationJournal(directory).load(operation.id()).entries().getFirst();
        assertTrue(afterRestart.check(STONE).matches());
        assertFalse(afterRestart.undoEligible());
        // In a separate live operation, authoritative readback establishes eligibility.
        var verified = new OperationJournal.Entry(A, AIR, STONE, STONE, 0);
        assertTrue(verified.undoEligible());
        assertFalse(verified.pending());
    }

    @Test void failedOrCancelledPartialOperationKeepsVerifiedActualNotAssumedIntended() throws Exception {
        for (OperationJournal.Status status : List.of(OperationJournal.Status.FAILED, OperationJournal.Status.CANCELLED)) {
            var journal = new OperationJournal(directory.resolve(status.name()));
            var operation = journal.begin("world", "minecraft:overworld", "partial");
            operation.recordIntent(A, AIR, CHEST);
            operation.verified(A, STONE);
            operation.recordIntent(B, AIR, CHEST);
            operation.finish(status, "partial result");
            var snapshot = journal.load(operation.id());
            assertEquals(status, snapshot.status());
            assertEquals(STONE, snapshot.entries().getFirst().expected());
            assertEquals(CHEST, snapshot.entries().getLast().expected());
            assertTrue(snapshot.entries().getLast().pending());
        }
    }

    @Test void cannotCompleteWithoutReadbackOrReplacePendingOrStaleImage() throws Exception {
        var journal = new OperationJournal(directory);
        var operation = journal.begin("world", "minecraft:overworld", "checks");
        assertThrows(IllegalStateException.class, () -> operation.verified(A, STONE));
        operation.recordIntent(A, AIR, STONE);
        assertThrows(IllegalArgumentException.class, () -> operation.finish(OperationJournal.Status.COMPLETED, "bad"));
        assertThrows(IllegalStateException.class, () -> operation.recordIntent(A, STONE, CHEST));
        operation.verified(A, STONE);
        assertThrows(IllegalStateException.class, () -> operation.verified(A, CHEST));
        assertThrows(IllegalStateException.class, () -> operation.recordIntent(A, AIR, CHEST));
        operation.recordIntent(A, STONE, CHEST);
        var pending = operation.snapshot().entries().getFirst();
        assertTrue(pending.pending());
        assertEquals(CHEST, pending.expected(), "Latest intent, not a stale previous verified image");
    }

    @Test void batchesPublishOnceAndRejectInvalidBatchWithoutPublishingPartialState() throws Exception {
        var publications = new java.util.concurrent.atomic.AtomicInteger();
        var journal = new OperationJournal(directory, (temporary, destination) -> {
            publications.incrementAndGet();
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        }, Clock.systemUTC());
        var operation = journal.begin("world", "minecraft:overworld", "batch");
        assertEquals(1, publications.get());
        operation.recordIntents(List.of(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST)));
        assertEquals(2, publications.get());
        assertEquals(2, journal.load(operation.id()).entries().size());
        operation.verifiedAll(Map.of(A, STONE, B, CHEST));
        assertEquals(3, publications.get());
        var unchanged = operation.snapshot();
        assertThrows(IllegalArgumentException.class, () -> operation.recordIntents(List.of(
                new OperationJournal.Intent(A, STONE, AIR), new OperationJournal.Intent(A, STONE, CHEST))));
        assertEquals(unchanged, operation.snapshot());
        assertEquals(3, publications.get());
        operation.recordIntents(List.of(new OperationJournal.Intent(A, STONE, AIR),
                new OperationJournal.Intent(B, CHEST, STONE)));
        var pending = operation.snapshot();
        assertThrows(IllegalStateException.class, () -> operation.verifiedAll(Map.of(A, AIR,
                new BlockPosition(99, 99, 99), AIR)));
        assertEquals(pending, operation.snapshot());
        assertEquals(4, publications.get());
        operation.verifiedAll(Map.of(A, AIR, B, STONE));
        assertEquals(AIR, operation.snapshot().entries().getFirst().before());
        assertEquals(List.of(A, B), operation.snapshot().entries().stream().map(OperationJournal.Entry::position).toList());
        assertEquals(5, publications.get());
    }

    @Test void knownUnappliedIntentCannotUndoAnExternalEditThatEqualsItsIntendedImage() throws Exception {
        var journal = new OperationJournal(directory);
        var operation = journal.begin("world", "minecraft:overworld", "compare-race");
        operation.recordIntents(List.of(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST)));
        // Native CAS saw an external AIR -> STONE edit before its own first mutation. Neither write began.
        operation.abortIntents(List.of(A, B));
        operation.finish(OperationJournal.Status.FAILED, "before-image conflict");
        var failed = journal.load(operation.id());
        assertTrue(failed.entries().isEmpty());
        assertTrue(failed.reverseEntries().stream().noneMatch(entry -> entry.check(STONE).matches()));
        assertEquals(failed, new OperationJournal(directory).load(operation.id()));
    }

    @Test void abortingRetouchRestoresPreviousVerifiedImageAndOriginalOrder() throws Exception {
        var journal = new OperationJournal(directory);
        var operation = journal.begin("world", "minecraft:overworld", "retouch-abort");
        operation.recordIntents(List.of(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST)));
        operation.verifiedAll(Map.of(A, STONE, B, CHEST));
        var beforeRetouch = operation.snapshot().entries();
        operation.recordIntent(A, STONE, CHEST);
        var pending = journal.load(operation.id()).entries().getFirst();
        assertEquals(STONE, pending.previousIntended());
        assertEquals(STONE, pending.previousVerified());
        operation.abortIntents(List.of(A));
        assertEquals(beforeRetouch, operation.snapshot().entries());
        assertEquals(beforeRetouch, journal.load(operation.id()).entries());
        assertTrue(operation.snapshot().entries().getFirst().check(STONE).matches());
        assertFalse(operation.snapshot().entries().getFirst().check(CHEST).matches());
        assertThrows(IllegalStateException.class, () -> operation.abortIntents(List.of(A)));
    }

    @Test void abortKeepsUncertainStartedEntryAndPreservesRemainingFirstTouchSequence() throws Exception {
        var journal = new OperationJournal(directory);
        var operation = journal.begin("world", "minecraft:overworld", "partial-abort");
        BlockPosition c = new BlockPosition(15, 42, 19);
        operation.recordIntents(List.of(new OperationJournal.Intent(A, AIR, STONE),
                new OperationJournal.Intent(B, AIR, CHEST), new OperationJournal.Intent(c, AIR, STONE)));
        // B may have begun and is deliberately left ambiguous; A and C are known unstarted.
        operation.abortIntents(List.of(A, c));
        var entry = operation.snapshot().entries().getFirst();
        assertEquals(B, entry.position());
        assertEquals(1, entry.sequence());
        assertTrue(entry.pending());
        assertEquals(CHEST, entry.expected());
        operation.recordIntent(c, AIR, STONE);
        assertEquals(List.of(1L, 2L), operation.snapshot().entries().stream().map(OperationJournal.Entry::sequence).toList());
        assertEquals(operation.snapshot(), journal.load(operation.id()));
    }

    @Test void abortPublicationFailureDoesNotErasePendingIntent() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        var journal = new OperationJournal(directory, (temporary, destination) -> {
            if (fail.get()) throw new IOException("disk error");
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        }, Clock.systemUTC());
        var operation = journal.begin("world", "minecraft:overworld", "abort-failure");
        operation.recordIntent(A, AIR, STONE);
        var pending = operation.snapshot();
        fail.set(true);
        assertThrows(IOException.class, () -> operation.abortIntents(List.of(A)));
        assertEquals(pending, operation.snapshot());
        assertEquals(pending, journal.load(operation.id()));
    }

    @Test void failedIntentPublicationDoesNotPublishInMemoryOrReplacePriorSnapshot() throws Exception {
        AtomicBoolean fail = new AtomicBoolean();
        var journal = new OperationJournal(directory, (temporary, destination) -> {
            if (fail.get()) throw new IOException("disk full before rename");
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        }, Clock.systemUTC());
        var operation = journal.begin("world", "minecraft:overworld", "failure");
        var before = operation.snapshot();
        fail.set(true);
        assertThrows(IOException.class, () -> operation.recordIntent(A, AIR, STONE));
        assertEquals(before, operation.snapshot());
        assertEquals(before, journal.load(operation.id()));
        fail.set(false);
        operation.recordIntent(A, AIR, STONE);
        var pending = operation.snapshot();
        fail.set(true);
        assertThrows(IOException.class, () -> operation.verified(A, STONE));
        assertEquals(pending, operation.snapshot());
        assertEquals(pending, journal.load(operation.id()));
        assertThrows(IOException.class, () -> operation.finish(OperationJournal.Status.FAILED, "disk failure"));
        assertEquals(OperationJournal.Status.RUNNING, operation.snapshot().status());
        try (var files = Files.list(directory)) { assertEquals(1, files.count()); }
    }

    @Test void futureCorruptAndMalformedJournalsAreRejectedWithoutDeletingContent() throws Exception {
        var journal = new OperationJournal(directory);
        var operation = journal.begin("world", "minecraft:overworld", "valid");
        operation.recordIntent(A, AIR, STONE);
        JsonObject original = OperationJournal.encode(operation.snapshot());
        Path file = directory.resolve(operation.id() + ".json");
        JsonObject future = original.deepCopy(); future.addProperty("version", 2);
        Files.writeString(file, future.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        assertThrows(IOException.class, () -> new OperationJournal(directory));
        assertEquals(future.toString(), Files.readString(file));
        JsonObject badOrder = original.deepCopy();
        badOrder.getAsJsonArray("entries").get(0).getAsJsonObject().addProperty("sequence", -1);
        Files.writeString(file, badOrder.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        JsonObject duplicate = original.deepCopy(); duplicate.getAsJsonArray("entries").add(duplicate.getAsJsonArray("entries").get(0));
        Files.writeString(file, duplicate.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        Files.writeString(file, "{not-json");
        assertThrows(IOException.class, () -> journal.load(operation.id()));
        assertThrows(IllegalArgumentException.class, () -> journal.load("../../outside"));
    }

    @Test void operationIdsMustMatchTheirFileAndNoArbitraryWorldPathIsOpened() throws Exception {
        var journal = new OperationJournal(directory.resolve("app-journals"));
        Path nonexistentWorld = directory.resolve("world-that-is-never-opened");
        var operation = journal.begin(nonexistentWorld.toString(), "example:dimension", "path-as-identity-only");
        assertFalse(Files.exists(nonexistentWorld));
        JsonObject value = OperationJournal.encode(operation.snapshot());
        value.addProperty("id", java.util.UUID.randomUUID().toString());
        Files.writeString(directory.resolve("app-journals").resolve(operation.id() + ".json"), value.toString());
        assertThrows(IOException.class, () -> journal.load(operation.id()));
    }
}
