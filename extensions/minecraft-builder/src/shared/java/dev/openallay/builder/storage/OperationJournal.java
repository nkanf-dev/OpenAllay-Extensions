package dev.openallay.builder.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.stream.JsonWriter;
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
                           long createdAt, long updatedAt, String detail, long checkpoint, List<Entry> entries) {
        public Snapshot {
            requireId(id);
            StrictJson.nonBlank(worldId, "worldId");
            StrictJson.identifier(dimension, "dimension");
            Objects.requireNonNull(label, "label");
            Objects.requireNonNull(status, "status");
            Objects.requireNonNull(detail, "detail");
            if (createdAt < 0 || updatedAt < createdAt || checkpoint < 0) throw StrictJson.invalid("Invalid journal timestamps");
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

    private static final String DELTA_FORMAT = "openallay:operation-delta";
    private final AtomicJsonFiles files;
    private final Clock clock;
    private final Map<String, Operation> active = new HashMap<>();


    public OperationJournal(Path directory) throws IOException { this(directory,AtomicJsonFiles.ATOMIC_MOVE,Clock.systemUTC()); }
    OperationJournal(Path directory,AtomicJsonFiles.Publisher publisher,Clock clock) throws IOException {
        files=new AtomicJsonFiles(directory,publisher);
        this.clock=Objects.requireNonNull(clock,"clock");
        recoverInterrupted();
    }

    public synchronized Operation begin(String worldId,String dimension,String label) throws IOException {
        String id;
        do { id=UUID.randomUUID().toString(); } while(files.exists(id));
        long now=clock.millis();
        Snapshot initial=new Snapshot(id,worldId,dimension,label,Status.RUNNING,now,now,"",0,List.of());
        files.write(id,writer -> writeSnapshot(writer,initial));
        Operation operation=new Operation(initial);
        active.put(id,operation);
        return operation;
    }

    private record StoredNames(List<String> bases,Map<String,java.util.SortedMap<Long,String>> deltas) {}
    private StoredNames names() throws IOException {
        List<String> bases=new ArrayList<>();
        Map<String,java.util.SortedMap<Long,String>> deltas=new HashMap<>();
        for(String name:files.names()) {
            int separator=name.indexOf("--");
            try {
                if(separator<0) { requireId(name); bases.add(name); }
                else {
                    String id=name.substring(0,separator), sequence=name.substring(separator+2);
                    requireId(id);
                    if(!sequence.matches("[0-9]{20}"))throw StrictJson.invalid("Invalid delta sequence filename");
                    long n=Long.parseLong(sequence);
                    if(n<1)throw StrictJson.invalid("Delta sequence must be positive");
                    var prior=deltas.computeIfAbsent(id,key->new java.util.TreeMap<>()).put(n,name);
                    if(prior!=null)throw StrictJson.invalid("Duplicate delta sequence");
                }
            } catch(IllegalArgumentException invalid) { throw new IOException("Invalid journal record filename: " + name,invalid); }
        }
        Set<String> baseIds=Set.copyOf(bases);
        for(String id:deltas.keySet())if(!baseIds.contains(id))throw new IOException("Orphan journal deltas without base: " + id);
        return new StoredNames(List.copyOf(bases),deltas);
    }

    public synchronized Snapshot load(String id) throws IOException { return load(id,names()); }
    private Snapshot load(String id,StoredNames names) throws IOException {
        requireId(id);
        try {
            JsonObject base=files.read(id);
            if(!FORMAT.equals(StrictJson.string(base.get("format"),"format")))throw StrictJson.invalid("Unsupported journal format");
            Snapshot initial=decode(base);
            if(!id.equals(initial.id()))throw StrictJson.invalid("Journal filename/id mismatch");
            Operation reconstructed=new Operation(initial);
            for(var record:names.deltas().getOrDefault(id,new java.util.TreeMap<>()).entrySet()) {
                if(record.getKey()<=initial.checkpoint())continue; // Covered by atomic checkpoint; cleanup may have crashed.
                if(initial.status()!=Status.RUNNING)throw StrictJson.invalid("Terminal checkpoint has uncovered deltas");
                if(record.getKey()!=reconstructed.sequence+1)throw StrictJson.invalid("Missing/out-of-order journal delta at sequence " + (reconstructed.sequence+1));
                reconstructed.replay(files.read(record.getValue()),record.getKey());
            }
            return reconstructed.snapshot();
        } catch(IllegalArgumentException | IllegalStateException invalid) { throw new IOException("Invalid operation journal " + id + ": " + invalid.getMessage(),invalid); }
    }

    public synchronized List<Snapshot> list() throws IOException {
        StoredNames names=names();
        List<Snapshot> result=new ArrayList<>();
        for(String id:names.bases())result.add(load(id,names));
        return List.copyOf(result);
    }

    /** Recovery only changes journal metadata. No world callbacks, replayed writes or rollback. */
    public synchronized List<Snapshot> recoverInterrupted() throws IOException {
        StoredNames names=names();
        List<Snapshot> recovered=new ArrayList<>();
        for(String id:names.bases()) {
            Snapshot old=load(id,names);
            if(old.status()!=Status.RUNNING || active.containsKey(id))continue;
            Snapshot next=new Snapshot(old.id(),old.worldId(),old.dimension(),old.label(),Status.INTERRUPTED,
                    old.createdAt(),Math.max(old.updatedAt(),clock.millis()),"Operation interrupted before a terminal status was persisted",
                    old.checkpoint(),old.entries());
            compact(next,names.deltas().getOrDefault(id,new java.util.TreeMap<>()));
            recovered.add(next);
        }
        return List.copyOf(recovered);
    }

    public final class Operation {
        private final String id,worldId,dimension,label;
        private final long createdAt,initialCheckpoint;
        private long updatedAt,sequence;
        private Status status;
        private String detail;
        private final java.util.LinkedHashMap<BlockPosition,Entry> entries=new java.util.LinkedHashMap<>();
        private long nextTouch;
        private IOException publicationFailure;
        private Operation(Snapshot snapshot) {
            id=snapshot.id();worldId=snapshot.worldId();dimension=snapshot.dimension();label=snapshot.label();
            createdAt=snapshot.createdAt();initialCheckpoint=snapshot.checkpoint();updatedAt=snapshot.updatedAt();sequence=snapshot.checkpoint();status=snapshot.status();detail=snapshot.detail();
            for(Entry entry:snapshot.entries()) { entries.put(entry.position(),entry);nextTouch=Math.max(nextTouch,entry.sequence()+1); }
        }
        public String id() { return id; }
        public Snapshot snapshot() {
            synchronized(OperationJournal.this) { return new Snapshot(id,worldId,dimension,label,status,createdAt,updatedAt,detail,sequence,List.copyOf(entries.values())); }
        }
        public boolean hasEntries() { synchronized(OperationJournal.this) { return !entries.isEmpty(); } }
        public void recordIntent(BlockPosition position,BlockSpec before,BlockSpec intended) throws IOException { recordIntents(List.of(new Intent(position,before,intended))); }
        public void recordIntents(List<Intent> intents) throws IOException {
            List<Intent> detached=List.copyOf(intents);
            synchronized(OperationJournal.this) {
                requireRunning();if(detached.isEmpty())return;
                Map<BlockPosition,Entry> replacement=prepareIntents(detached);
                publish("intents",writer -> {
                    writer.name("intents").beginArray();
                    for(Intent intent:detached) {
                        writer.beginObject();
                        writer.name("position");writePosition(writer,intent.position());
                        writer.name("before");writeBlock(writer,intent.before());
                        writer.name("intended");writeBlock(writer,intent.intended());
                        writer.endObject();
                    }
                    writer.endArray();
                });
                replacement.forEach(entries::put);
                for(Entry entry:replacement.values())nextTouch=Math.max(nextTouch,entry.sequence()+1);
            }
        }
        private Map<BlockPosition,Entry> prepareIntents(List<Intent> intents) {
            Map<BlockPosition,Entry> replacement=new java.util.LinkedHashMap<>();long touch=nextTouch;
            for(Intent intent:intents) {
                if(replacement.containsKey(intent.position()))throw StrictJson.invalid("Duplicate position in intent batch");
                Entry prior=entries.get(intent.position());Entry next;
                if(prior==null)next=new Entry(intent.position(),intent.before(),intent.intended(),null,touch++);
                else {
                    if(prior.pending())throw new IllegalStateException("Previous intent has not been verified");
                    if(!prior.verified().equals(intent.before()))throw new IllegalStateException("Position changed since previous verified write");
                    next=new Entry(intent.position(),prior.before(),intent.intended(),null,prior.sequence(),prior.intended(),prior.verified());
                }
                replacement.put(intent.position(),next);
            }
            return replacement;
        }
        public void verified(BlockPosition position,BlockSpec actual) throws IOException { verifiedAll(Map.of(position,actual)); }
        public void verifiedAll(Map<BlockPosition,BlockSpec> actual) throws IOException { resolve(actual,List.of()); }
        public void abortIntents(Collection<BlockPosition> knownUnstarted) throws IOException { resolve(Map.of(),knownUnstarted); }
        /** Actual readbacks and known unstarted withdrawals share one durable outcome publication. */
        public void resolve(Map<BlockPosition,BlockSpec> actual,Collection<BlockPosition> knownUnstarted) throws IOException {
            Map<BlockPosition,BlockSpec> detached=Map.copyOf(actual);Set<BlockPosition> aborted=Set.copyOf(knownUnstarted);
            synchronized(OperationJournal.this) {
                requireRunning();if(detached.isEmpty() && aborted.isEmpty())return;
                Map<BlockPosition,Entry> replacement=prepareOutcome(detached,aborted);
                publish("outcome",writer -> {
                    writer.name("actual").beginArray();
                    for(var image:detached.entrySet()) {
                        writer.beginObject();
                        writer.name("position");writePosition(writer,image.getKey());
                        writer.name("actual");writeBlock(writer,image.getValue());
                        writer.endObject();
                    }
                    writer.endArray().name("unstarted").beginArray();
                    for(BlockPosition position:aborted)writePosition(writer,position);
                    writer.endArray();
                });
                applyOutcome(replacement);
            }
        }
        private Map<BlockPosition,Entry> prepareOutcome(Map<BlockPosition,BlockSpec> actual,Set<BlockPosition> aborted) {
            Map<BlockPosition,Entry> replacement=new HashMap<>();
            for(BlockPosition position:aborted) {
                Entry old=pending(position);
                replacement.put(position,old.previousVerified()==null?null:new Entry(position,old.before(),old.previousIntended(),old.previousVerified(),old.sequence()));
            }
            actual.forEach((position,state)-> {
                if(aborted.contains(position))throw StrictJson.invalid("Outcome position is both applied and unstarted");
                Entry old=pending(position);replacement.put(position,new Entry(position,old.before(),old.intended(),state,old.sequence()));
            });
            return replacement;
        }
        private Entry pending(BlockPosition position) {
            Entry old=entries.get(position);
            if(old==null)throw new IllegalStateException("No persisted intent for position " + position);
            if(!old.pending())throw new IllegalStateException("Intent has already been verified");
            return old;
        }
        private void applyOutcome(Map<BlockPosition,Entry> replacement) {
            boolean removed=false;
            for(var item:replacement.entrySet()) {
                if(item.getValue()==null) {entries.remove(item.getKey());removed=true;}
                else entries.put(item.getKey(),item.getValue());
            }
            if(removed) {
                // Replacements never move an existing key. Insertion order is first-touch
                // order, so only the tail determines the next sequence after withdrawals.
                var last=entries.lastEntry();
                nextTouch=last==null?0:last.getValue().sequence()+1;
            }
        }
        private void publish(String kind,AtomicJsonFiles.JsonContent content) throws IOException {
            long next=Math.addExact(sequence,1),time=Math.max(updatedAt,clock.millis());
            try {
                files.write(deltaName(id,next),writer -> {
                    writer.beginObject();
                    writer.name("format").value(DELTA_FORMAT);
                    writer.name("id").value(id);
                    writer.name("sequence").value(next);
                    writer.name("updatedAt").value(time);
                    writer.name("kind").value(kind);
                    content.write(writer);
                    writer.endObject();
                }); // fsync/atomic publish before memory admission or world write.
            } catch(IOException failure) {
                // Rename/force failure may have published a record. Never overwrite its sequence
                // or compact older memory over it; recovery must inspect the actual durable cut.
                publicationFailure=failure;
                throw failure;
            }
            sequence=next;updatedAt=time;
        }
        private void replay(JsonObject delta,long expected) {
            StrictJson.fields(delta,Set.of("format","id","sequence","updatedAt","kind"),Set.of("intents","actual","unstarted"));
            if(!DELTA_FORMAT.equals(StrictJson.string(delta.get("format"),"format")) || !id.equals(StrictJson.string(delta.get("id"),"id")) || StrictJson.longInteger(delta.get("sequence"),"sequence")!=expected)
                throw StrictJson.invalid("Delta identity/sequence mismatch");
            long time=StrictJson.longInteger(delta.get("updatedAt"),"updatedAt");if(time<updatedAt)throw StrictJson.invalid("Delta timestamp moved backwards");
            String kind=StrictJson.string(delta.get("kind"),"kind");
            if(kind.equals("intents")) {
                StrictJson.fields(delta,Set.of("format","id","sequence","updatedAt","kind","intents"),Set.of());
                List<Intent> intents=new ArrayList<>();
                for(JsonElement element:StrictJson.array(delta.get("intents"),"intents")) {JsonObject value=StrictJson.object(element,"intent");StrictJson.fields(value,Set.of("position","before","intended"),Set.of());intents.add(new Intent(BlockPosition.fromJson(value.get("position")),BlockSpec.fromJson(StrictJson.object(value.get("before"),"before")),BlockSpec.fromJson(StrictJson.object(value.get("intended"),"intended"))));}
                if(intents.isEmpty())throw StrictJson.invalid("Empty intent delta");
                var replacement=prepareIntents(intents);replacement.forEach(entries::put);for(Entry entry:replacement.values())nextTouch=Math.max(nextTouch,entry.sequence()+1);
            } else if(kind.equals("outcome")) {
                StrictJson.fields(delta,Set.of("format","id","sequence","updatedAt","kind","actual","unstarted"),Set.of());
                Map<BlockPosition,BlockSpec> actual=new HashMap<>();Set<BlockPosition> aborted=new HashSet<>();
                for(JsonElement element:StrictJson.array(delta.get("actual"),"actual")) {JsonObject value=StrictJson.object(element,"image");StrictJson.fields(value,Set.of("position","actual"),Set.of());if(actual.put(BlockPosition.fromJson(value.get("position")),BlockSpec.fromJson(StrictJson.object(value.get("actual"),"actual")))!=null)throw StrictJson.invalid("Duplicate actual image");}
                for(JsonElement value:StrictJson.array(delta.get("unstarted"),"unstarted"))if(!aborted.add(BlockPosition.fromJson(value)))throw StrictJson.invalid("Duplicate unstarted position");
                if(actual.isEmpty() && aborted.isEmpty())throw StrictJson.invalid("Empty outcome delta");
                applyOutcome(prepareOutcome(actual,aborted));
            } else throw StrictJson.invalid("Unknown delta kind: " + kind);
            sequence=expected;updatedAt=time;
        }
        public void finish(Status terminal,String message) throws IOException {
            synchronized(OperationJournal.this) {
                requireRunning();if(terminal==Status.RUNNING)throw StrictJson.invalid("Terminal status required");
                Snapshot next=new Snapshot(id,worldId,dimension,label,terminal,createdAt,Math.max(updatedAt,clock.millis()),message==null?"":message,sequence,List.copyOf(entries.values()));
                try { compact(next,initialCheckpoint); }
                catch(IOException failure) { publicationFailure=failure;throw failure; }
                status=terminal;updatedAt=next.updatedAt();detail=next.detail();active.remove(id);
            }
        }
        private void requireRunning() throws IOException {
            if(publicationFailure!=null)throw new IOException("Journal delta publication failed; reload/recovery required",publicationFailure);
            if(status!=Status.RUNNING)throw new IllegalStateException("Operation is already terminal");
        }
    }

    private static String deltaName(String id,long sequence) {
        String digits=Long.toString(sequence);
        return id + "--" + "00000000000000000000".substring(digits.length()) + digits;
    }

    private void compact(Snapshot snapshot,long previousCheckpoint) throws IOException {
        files.write(snapshot.id(),writer -> writeSnapshot(writer,snapshot)); // Force checkpoint BEFORE removing deltas.
        // This live handle published exactly this contiguous range. Do not scan unrelated
        // historical operations at every finish: repeated small operations must stay linear.
        for(long sequence=previousCheckpoint;sequence<snapshot.checkpoint();)files.delete(deltaName(snapshot.id(),++sequence));
    }

    private void compact(Snapshot snapshot,java.util.SortedMap<Long,String> records) throws IOException {
        files.write(snapshot.id(),writer -> writeSnapshot(writer,snapshot));
        // Startup already collected and checked these filenames. Reuse that one directory scan.
        for(var record:records.entrySet())if(record.getKey()<=snapshot.checkpoint())files.delete(record.getValue());
    }
    private static void requireId(String id) {
        if(id==null)throw StrictJson.invalid("Operation id is required");
        try { if(!UUID.fromString(id).toString().equals(id))throw StrictJson.invalid("Noncanonical operation id"); }
        catch(IllegalArgumentException invalid) { throw StrictJson.invalid("Operation id must be a canonical UUID"); }
    }

    private static void writePosition(JsonWriter writer,BlockPosition position) throws IOException {
        writer.beginArray().value(position.x()).value(position.y()).value(position.z()).endArray();
    }

    private static void writeBlock(JsonWriter writer,BlockSpec block) throws IOException {
        writer.beginObject().name("id").value(block.id()).name("properties").beginObject();
        for(var property:block.properties().entrySet())writer.name(property.getKey()).value(property.getValue());
        writer.endObject();
        if(block.blockEntity()!=null)writer.name("blockEntity").value(block.blockEntity());
        writer.endObject();
    }

    private static void writeSnapshot(JsonWriter writer,Snapshot snapshot) throws IOException {
        writer.beginObject();
        writer.name("format").value(FORMAT);
        writer.name("id").value(snapshot.id());
        writer.name("worldId").value(snapshot.worldId());
        writer.name("dimension").value(snapshot.dimension());
        writer.name("label").value(snapshot.label());
        writer.name("status").value(snapshot.status().name());
        writer.name("createdAt").value(snapshot.createdAt());
        writer.name("updatedAt").value(snapshot.updatedAt());
        writer.name("detail").value(snapshot.detail());
        writer.name("checkpoint").value(snapshot.checkpoint());
        writer.name("entries").beginArray();
        for(Entry entry:snapshot.entries()) {
            writer.beginObject();
            writer.name("position");writePosition(writer,entry.position());
            writer.name("before");writeBlock(writer,entry.before());
            writer.name("intended");writeBlock(writer,entry.intended());
            if(entry.verified()!=null) {writer.name("verified");writeBlock(writer,entry.verified());}
            if(entry.previousVerified()!=null) {
                writer.name("previousIntended");writeBlock(writer,entry.previousIntended());
                writer.name("previousVerified");writeBlock(writer,entry.previousVerified());
            }
            writer.name("sequence").value(entry.sequence());
            writer.endObject();
        }
        writer.endArray().endObject();
    }

    static JsonObject encode(Snapshot snapshot) {
        JsonObject result=new JsonObject();result.addProperty("format",FORMAT);result.addProperty("id",snapshot.id());result.addProperty("worldId",snapshot.worldId());
        result.addProperty("dimension",snapshot.dimension());result.addProperty("label",snapshot.label());result.addProperty("status",snapshot.status().name());result.addProperty("createdAt",snapshot.createdAt());result.addProperty("updatedAt",snapshot.updatedAt());result.addProperty("detail",snapshot.detail());result.addProperty("checkpoint",snapshot.checkpoint());
        JsonArray entries=new JsonArray();
        for(Entry entry:snapshot.entries()) {
            JsonObject value=new JsonObject();value.add("position",entry.position().toJson());value.add("before",entry.before().toJson());value.add("intended",entry.intended().toJson());
            if(entry.verified()!=null)value.add("verified",entry.verified().toJson());
            if(entry.previousVerified()!=null) {value.add("previousIntended",entry.previousIntended().toJson());value.add("previousVerified",entry.previousVerified().toJson());}
            value.addProperty("sequence",entry.sequence());entries.add(value);
        }
        result.add("entries",entries);return result;
    }
    static Snapshot decode(JsonObject object) {
        StrictJson.fields(object,Set.of("format","id","worldId","dimension","label","status","createdAt","updatedAt","detail","checkpoint","entries"),Set.of());
        if(!FORMAT.equals(StrictJson.string(object.get("format"),"format")))throw StrictJson.invalid("Unsupported journal format");
        List<Entry> entries=new ArrayList<>();
        for(JsonElement item:StrictJson.array(object.get("entries"),"entries")) {
            JsonObject value=StrictJson.object(item,"entry");StrictJson.fields(value,Set.of("position","before","intended","sequence"),Set.of("verified","previousIntended","previousVerified"));
            entries.add(new Entry(BlockPosition.fromJson(value.get("position")),BlockSpec.fromJson(StrictJson.object(value.get("before"),"before")),BlockSpec.fromJson(StrictJson.object(value.get("intended"),"intended")),value.has("verified")?BlockSpec.fromJson(StrictJson.object(value.get("verified"),"verified")):null,StrictJson.longInteger(value.get("sequence"),"sequence"),value.has("previousIntended")?BlockSpec.fromJson(StrictJson.object(value.get("previousIntended"),"previousIntended")):null,value.has("previousVerified")?BlockSpec.fromJson(StrictJson.object(value.get("previousVerified"),"previousVerified")):null));
        }
        return new Snapshot(StrictJson.string(object.get("id"),"id"),StrictJson.string(object.get("worldId"),"worldId"),StrictJson.string(object.get("dimension"),"dimension"),StrictJson.string(object.get("label"),"label"),Status.valueOf(StrictJson.string(object.get("status"),"status")),StrictJson.longInteger(object.get("createdAt"),"createdAt"),StrictJson.longInteger(object.get("updatedAt"),"updatedAt"),StrictJson.string(object.get("detail"),"detail"),StrictJson.longInteger(object.get("checkpoint"),"checkpoint"),entries);
    }
}
