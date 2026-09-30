package dev.openallay.builder.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/**
 * Worker-thread write-ahead block journal. One instance owns an application journal directory.
 * A durable intent must complete before submitting a world mutation. Verification is persisted after
 * authoritative readback. Recovery marks abandoned RUNNING records INTERRUPTED; it never replays them.
 */
public final class OperationJournal {
    public static final String FORMAT = "openallay:operation-journal";
    public static final int VERSION = 1;

    public enum Status { RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED }

    public record UndoCheck(boolean matches, BlockSpec expected, BlockSpec current) {
        public UndoCheck {
            Objects.requireNonNull(expected, "expected");
            Objects.requireNonNull(current, "current");
            if (matches != expected.equals(current)) throw StrictJson.invalid("Inconsistent undo comparison");
        }
    }

    /** One detached write-ahead mutation request. */
    public record Intent(BlockPosition position, BlockSpec before, BlockSpec intended) {
        public Intent {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(intended, "intended");
        }
    }

    /** sequence is the original first-touch order, even when a position is edited again. */
    public record Entry(BlockPosition position, BlockSpec before, BlockSpec intended, BlockSpec verified,
                        long sequence, BlockSpec previousIntended, BlockSpec previousVerified) {
        public Entry {
            Objects.requireNonNull(position, "position");
            Objects.requireNonNull(before, "before");
            Objects.requireNonNull(intended, "intended");
            if (sequence < 0) throw StrictJson.invalid("sequence must be nonnegative");
            if ((previousIntended == null) != (previousVerified == null))
                throw StrictJson.invalid("Retouch history requires both previous images");
            if (verified != null && previousVerified != null)
                throw StrictJson.invalid("Verified entry must not retain pending retouch history");
        }

        public Entry(BlockPosition position, BlockSpec before, BlockSpec intended, BlockSpec verified, long sequence) {
            this(position, before, intended, verified, sequence, null, null);
        }

        /** Diagnostic postimage. An unverified intended image is NOT evidence that our write occurred. */
        public BlockSpec expected() { return verified == null ? intended : verified; }
        public boolean pending() { return verified == null; }
        /** Default undo must skip/report pending entries even if current equals intended. */
        public boolean undoEligible() { return verified != null; }
        /** Pure image comparison; callers must also require undoEligible() before any restore. */
        public UndoCheck check(BlockSpec current) {
            return new UndoCheck(expected().equals(current), expected(), current);
        }
    }

    public record Snapshot(String id, String worldId, String dimension, String label, Status status,
                           long createdAt, long updatedAt, String detail, List<Entry> entries) {
        public Snapshot {
            requireId(id);
            StrictJson.nonBlank(worldId, "worldId");
            StrictJson.identifier(dimension, "dimension");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (createdAt < 0 || updatedAt < createdAt) throw StrictJson.invalid("Invalid journal timestamps");
            entries = List.copyOf(entries);
            Set<BlockPosition> positions = new HashSet<>();
            long previousSequence = -1;
            for (Entry entry : entries) {
                if (entry.sequence() <= previousSequence || !positions.add(entry.position()))
                    throw StrictJson.invalid("Invalid first-touch order or duplicate journal position");
                previousSequence = entry.sequence();
                if (status == Status.COMPLETED && entry.pending())
                    throw StrictJson.invalid("Completed journal contains an unverified intent");
            }
        }

        public List<Entry> reverseEntries() {
            List<Entry> reverse = new ArrayList<>(entries);
            Collections.reverse(reverse);
            return List.copyOf(reverse);
        }
    }

    private final AtomicJsonFiles files;
    private final Clock clock;
    private final Map<String, Operation> active = new HashMap<>();

    public OperationJournal(Path directory) throws IOException {
        this(directory, AtomicJsonFiles.ATOMIC_MOVE, Clock.systemUTC());
    }

    OperationJournal(Path directory, AtomicJsonFiles.Publisher publisher, Clock clock) throws IOException {
        this.files = new AtomicJsonFiles(directory, publisher);
        this.clock = Objects.requireNonNull(clock, "clock");
        recoverInterrupted();
    }

    public synchronized Operation begin(String worldId, String dimension, String label) throws IOException {
        String id;
        do { id = UUID.randomUUID().toString(); } while (files.exists(id));
        long now = clock.millis();
        Snapshot snapshot = new Snapshot(id, worldId, dimension, label, Status.RUNNING, now, now, "", List.of());
        persist(snapshot);
        Operation operation = new Operation(snapshot);
        active.put(id, operation);
        return operation;
    }

    public synchronized Snapshot load(String id) throws IOException {
        requireId(id);
        try {
            Snapshot snapshot = decode(files.read(id));
            if (!id.equals(snapshot.id())) throw StrictJson.invalid("Journal filename/id mismatch");
            return snapshot;
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid operation journal " + id + ": " + e.getMessage(), e);
        }
    }

    public synchronized List<Snapshot> list() throws IOException {
        List<Snapshot> snapshots = new ArrayList<>();
        for (String id : files.names()) snapshots.add(load(id));
        return List.copyOf(snapshots);
    }

    /** Persist recovery state only. This method has no world callback, write backend or replay path. */
    public synchronized List<Snapshot> recoverInterrupted() throws IOException {
        List<Snapshot> recovered = new ArrayList<>();
        for (Snapshot old : list()) {
            if (old.status() != Status.RUNNING || active.containsKey(old.id())) continue;
            Snapshot snapshot = transition(old, Status.INTERRUPTED,
                    "Operation interrupted before a terminal status was persisted", old.entries());
            persist(snapshot);
            recovered.add(snapshot);
        }
        return List.copyOf(recovered);
    }

    public final class Operation {
        private Snapshot snapshot;
        private Operation(Snapshot snapshot) { this.snapshot = snapshot; }
        public String id() {
            synchronized (OperationJournal.this) { return snapshot.id(); }
        }

        public Snapshot snapshot() {
            synchronized (OperationJournal.this) { return snapshot; }
        }

        /** First before-image and insertion order survive every subsequent touch of this position. */
        public void recordIntent(BlockPosition position, BlockSpec before, BlockSpec intended) throws IOException {
            recordIntents(List.of(new Intent(position, before, intended)));
        }

        /** Persist an entire scheduling quantum before its first world write. All input is validated first. */
        public void recordIntents(List<Intent> intents) throws IOException {
            List<Intent> detached = List.copyOf(intents);
            synchronized (OperationJournal.this) {
                requireRunning();
                if (detached.isEmpty()) return;
                List<Entry> entries = new ArrayList<>(snapshot.entries());
                Map<BlockPosition, Integer> indexes = indexEntries(entries);
                Set<BlockPosition> touched = new HashSet<>();
                for (Intent intent : detached) {
                    if (!touched.add(intent.position()))
                        throw new IllegalArgumentException("Duplicate position in intent batch");
                    Integer index = indexes.get(intent.position());
                    if (index == null) {
                        long sequence = entries.isEmpty() ? 0 : Math.addExact(entries.getLast().sequence(), 1);
                        entries.add(new Entry(intent.position(), intent.before(), intent.intended(), null, sequence));
                    } else {
                        Entry prior = entries.get(index);
                        if (prior.pending()) throw new IllegalStateException("Previous intent has not been verified");
                        if (!prior.verified().equals(intent.before()))
                            throw new IllegalStateException("Position changed since previous verified write");
                        entries.set(index, new Entry(intent.position(), prior.before(), intent.intended(), null,
                                prior.sequence(), prior.intended(), prior.verified()));
                    }
                }
                replace(transition(snapshot, Status.RUNNING, "", entries));
            }
        }

        /** Stores actual readback even when it differs from intended. Caller determines operation success. */
        public void verified(BlockPosition position, BlockSpec actual) throws IOException {
            verifiedAll(Map.of(position, actual));
        }

        /** Persist the actual readbacks from one completed scheduling quantum in one atomic publication. */
        public void verifiedAll(Map<BlockPosition, BlockSpec> actualImages) throws IOException {
            Map<BlockPosition, BlockSpec> detached = Map.copyOf(actualImages);
            synchronized (OperationJournal.this) {
                requireRunning();
                if (detached.isEmpty()) return;
                List<Entry> entries = new ArrayList<>(snapshot.entries());
                Map<BlockPosition, Integer> indexes = indexEntries(entries);
                for (Map.Entry<BlockPosition, BlockSpec> image : detached.entrySet()) {
                    Integer index = indexes.get(image.getKey());
                    if (index == null) throw new IllegalStateException("No persisted intent for position " + image.getKey());
                    Entry old = entries.get(index);
                    if (!old.pending()) throw new IllegalStateException("Intent has already been verified");
                    entries.set(index, new Entry(old.position(), old.before(), old.intended(), image.getValue(), old.sequence()));
                }
                replace(transition(snapshot, Status.RUNNING, "", entries));
            }
        }

        /**
         * Withdraw intents that the native owner KNOWS never began a mutation. This is not rollback.
         * Use it for failed compare-before-write or unstarted work, never for ambiguous started work.
         * A first touch disappears; a retouch restores its prior durable verified image and order.
         */
        public void abortIntents(Collection<BlockPosition> knownNotApplied) throws IOException {
            Set<BlockPosition> positions = Set.copyOf(knownNotApplied);
            synchronized (OperationJournal.this) {
                requireRunning();
                if (positions.isEmpty()) return;
                Map<BlockPosition, Integer> indexes = indexEntries(snapshot.entries());
                for (BlockPosition position : positions) {
                    Integer index = indexes.get(position);
                    if (index == null) throw new IllegalStateException("No persisted intent for position " + position);
                    if (!snapshot.entries().get(index).pending())
                        throw new IllegalStateException("Cannot abort a verified mutation");
                }
                List<Entry> entries = new ArrayList<>();
                for (Entry entry : snapshot.entries()) {
                    if (!positions.contains(entry.position())) {
                        entries.add(entry);
                    } else if (entry.previousVerified() != null) {
                        entries.add(new Entry(entry.position(), entry.before(), entry.previousIntended(),
                                entry.previousVerified(), entry.sequence()));
                    }
                }
                replace(transition(snapshot, Status.RUNNING, "", entries));
            }
        }

        public void finish(Status status, String detail) throws IOException {
            synchronized (OperationJournal.this) {
                requireRunning();
                if (status == Status.RUNNING) throw new IllegalArgumentException("Terminal status required");
                replace(transition(snapshot, status, detail == null ? "" : detail, snapshot.entries()));
                active.remove(id());
            }
        }

        private void requireRunning() {
            if (snapshot.status() != Status.RUNNING) throw new IllegalStateException("Operation is already terminal");
        }

        private void replace(Snapshot replacement) throws IOException {
            persist(replacement); // Publish state in memory only after durable publication succeeds.
            snapshot = replacement;
        }
    }

    private Snapshot transition(Snapshot old, Status status, String detail, List<Entry> entries) {
        return new Snapshot(old.id(), old.worldId(), old.dimension(), old.label(), status, old.createdAt(),
                Math.max(old.updatedAt(), clock.millis()), detail, entries);
    }

    private static Map<BlockPosition, Integer> indexEntries(List<Entry> entries) {
        Map<BlockPosition, Integer> result = new HashMap<>();
        for (int i = 0; i < entries.size(); i++) result.put(entries.get(i).position(), i);
        return result;
    }

    private void persist(Snapshot snapshot) throws IOException { files.write(snapshot.id(), encode(snapshot)); }

    private static void requireId(String id) {
        if (id == null) throw StrictJson.invalid("Operation id is required");
        try {
            if (!UUID.fromString(id).toString().equals(id)) throw StrictJson.invalid("Noncanonical operation id");
        } catch (IllegalArgumentException e) {
            throw StrictJson.invalid("Operation id must be a canonical UUID");
        }
    }

    static JsonObject encode(Snapshot snapshot) {
        JsonObject result = new JsonObject();
        result.addProperty("format", FORMAT);
        result.addProperty("version", VERSION);
        result.addProperty("id", snapshot.id());
        result.addProperty("worldId", snapshot.worldId());
        result.addProperty("dimension", snapshot.dimension());
        result.addProperty("label", snapshot.label());
        result.addProperty("status", snapshot.status().name());
        result.addProperty("createdAt", snapshot.createdAt());
        result.addProperty("updatedAt", snapshot.updatedAt());
        result.addProperty("detail", snapshot.detail());
        JsonArray entries = new JsonArray();
        for (Entry entry : snapshot.entries()) {
            JsonObject value = new JsonObject();
            value.add("position", entry.position().toJson());
            value.add("before", entry.before().toJson());
            value.add("intended", entry.intended().toJson());
            if (entry.verified() != null) value.add("verified", entry.verified().toJson());
            if (entry.previousVerified() != null) {
                value.add("previousIntended", entry.previousIntended().toJson());
                value.add("previousVerified", entry.previousVerified().toJson());
            }
            value.addProperty("sequence", entry.sequence());
            entries.add(value);
        }
        result.add("entries", entries);
        return result;
    }

    static Snapshot decode(JsonObject object) {
        StrictJson.fields(object, Set.of("format", "version", "id", "worldId", "dimension", "label", "status",
                "createdAt", "updatedAt", "detail", "entries"), Set.of());
        if (!FORMAT.equals(StrictJson.string(object.get("format"), "format")))
            throw StrictJson.invalid("Unsupported journal format");
        if (StrictJson.integer(object.get("version"), "version") != VERSION)
            throw StrictJson.invalid("Unsupported journal schema version");
        List<Entry> entries = new ArrayList<>();
        for (JsonElement item : StrictJson.array(object.get("entries"), "entries")) {
            JsonObject value = StrictJson.object(item, "entry");
            StrictJson.fields(value, Set.of("position", "before", "intended", "sequence"),
                    Set.of("verified", "previousIntended", "previousVerified"));
            entries.add(new Entry(BlockPosition.fromJson(value.get("position")),
                    BlockSpec.fromJson(StrictJson.object(value.get("before"), "before")),
                    BlockSpec.fromJson(StrictJson.object(value.get("intended"), "intended")),
                    value.has("verified") ? BlockSpec.fromJson(StrictJson.object(value.get("verified"), "verified")) : null,
                    StrictJson.longInteger(value.get("sequence"), "sequence"),
                    value.has("previousIntended") ? BlockSpec.fromJson(StrictJson.object(value.get("previousIntended"), "previousIntended")) : null,
                    value.has("previousVerified") ? BlockSpec.fromJson(StrictJson.object(value.get("previousVerified"), "previousVerified")) : null));
        }
        return new Snapshot(StrictJson.string(object.get("id"), "id"),
                StrictJson.string(object.get("worldId"), "worldId"),
                StrictJson.string(object.get("dimension"), "dimension"),
                StrictJson.string(object.get("label"), "label"),
                Status.valueOf(StrictJson.string(object.get("status"), "status")),
                StrictJson.longInteger(object.get("createdAt"), "createdAt"),
                StrictJson.longInteger(object.get("updatedAt"), "updatedAt"),
                StrictJson.string(object.get("detail"), "detail"), entries);
    }
}
