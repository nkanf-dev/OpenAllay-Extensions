package dev.openallay.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.BlockSpec;
import dev.openallay.builder.storage.OperationJournal;
import dev.openallay.builder.storage.TemplateStore;
import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.BlockPos;

/** Worker facade. Public values are detached JSON; Minecraft objects stay in owner actions. */
public final class BuilderSession implements AutoCloseable {
    // Scheduling quantum, not a volume/feature limit.
    static final int QUANTUM = 8192;
    static final int WRITE_QUANTUM = 1024;
    private static final Map<Path, OperationJournal> JOURNALS = new ConcurrentHashMap<>();
    private final SessionInvocation frame;
    private final BuilderBackend binding;
    private final OwnerThreadBridge bridge;
    private final Thread worker = Thread.currentThread();
    private final long dispatchStart;
    private Instant captureStarted = Instant.now();
    private final TemplateStore templates;
    private final OperationJournal journal;
    private final String label;
    private OperationJournal.Operation operation;
    private String lastOperation;
    private String state = "ready";
    private String detail = "";
    private long reads;
    private long paletteProvenCells;
    private long writes;
    private boolean closed;
    private boolean cancelled;

    BuilderSession(SessionInvocation frame, BuilderBackend binding, OwnerThreadBridge bridge, String optionsJson) {
        this.frame = frame;
        this.binding = binding;
        this.bridge = bridge;
        this.dispatchStart = binding.dispatches();
        JsonObject options = JsonParser.parseString(optionsJson == null || optionsJson.isBlank() ? "{}" : optionsJson).getAsJsonObject();
        if (options.has("dimension") && !binding.dimension().equals(options.get("dimension").getAsString()))
            throw new BuilderException("dimension_mismatch", "Requested dimension is not the active bound dimension: " + binding.dimension());
        this.label = options.has("label") ? options.get("label").getAsString() : "Builder operation";
        try {
            templates = new TemplateStore(binding.artifacts().resolve("templates"));
            Path journalRoot = binding.artifacts().resolve("journals");
            synchronized (JOURNALS) {
                OperationJournal existing = JOURNALS.get(journalRoot);
                if (existing == null) {
                    existing = new OperationJournal(journalRoot);
                    JOURNALS.put(journalRoot, existing);
                }
                journal = existing;
            }
        } catch (IOException failure) { throw io(failure); }
    }

    private void active() {
        bridge.checkWorker();
        if (closed || cancelled) throw new BuilderException("session_closed", "Builder session is closed or cancelled");
    }

    public String context() { active(); String result = binding.context(); evidence("context", 1); return result; }
    public String player() { return JsonParser.parseString(context()).getAsJsonObject().get("player").toString(); }
    public String version() { return JsonParser.parseString(context()).getAsJsonObject().get("version").getAsString(); }

    public String read(int x, int y, int z) {
        active();
        String result = binding.call(() -> readOwner(new BlockPos(x, y, z)));
        reads++;
        evidence("read", 1);
        return result;
    }

    /** Fresh detached full images in caller order; duplicates are observed and returned independently. */
    public String readPositions(String positionsJson) {
        active();
        captureStarted=Instant.now();
        JsonArray input=JsonParser.parseString(positionsJson).getAsJsonArray();
        List<BlockPosition> positions=new ArrayList<>(input.size());
        for(JsonElement value:input) {
            JsonObject position=value.getAsJsonObject();
            positions.add(new BlockPosition(BuilderBounds.integer(position,"x"),BuilderBounds.integer(position,"y"),BuilderBounds.integer(position,"z")));
        }
        StringBuilder result=new StringBuilder("[");
        for(int offset=0;offset<positions.size();) {
            active();
            int start=offset,size=Math.min(QUANTUM,positions.size()-offset);
            List<Captured> slice=binding.call(() -> {
                List<Captured> values=new ArrayList<>();
                long deadline=binding.sliceDeadline();
                for(int i=0;i<size;i++) {
                    if(i>0&&System.nanoTime()>=deadline)break;
                    BlockPosition position=positions.get(start+i);
                    values.add(new Captured(position,readOwner(pos(position))));
                }
                return List.copyOf(values);
            });
            for(Captured value:slice) {
                if(result.length()>1)result.append(',');
                BlockPosition position=value.position();
                result.append("{\"x\":").append(position.x()).append(",\"y\":").append(position.y())
                        .append(",\"z\":").append(position.z()).append(",\"state\":").append(value.state()).append('}');
            }
            offset+=slice.size();reads+=slice.size();
        }
        active();
        evidence("read-positions",positions.size());
        return result.append(']').toString();
    }

    private String readOwner(BlockPos pos) {
        return binding.read(pos);
    }

    public String readRegion(String boundsJson) {
        active();
        captureStarted = Instant.now();
        BuilderBounds bounds = BuilderBounds.parse(boundsJson);
        JsonObject request = JsonParser.parseString(boundsJson).getAsJsonObject();
        boolean omitAir = request.has("omitAir") && request.get("omitAir").getAsBoolean();
        if(omitAir)return readSparseRegion(bounds);
        StringBuilder result = new StringBuilder("[");
        long volume = bounds.volume();
        boolean first = true;
        for (long offset = 0; offset < volume;) {
            active();
            long start = offset;
            int size = (int)Math.min(QUANTUM, volume - offset);
            CaptureBatch part = binding.call(() -> {
                List<Captured> values = new ArrayList<>();
                long deadline = binding.sliceDeadline();
                int inspected = 0;
                while (inspected < size && (inspected == 0 || System.nanoTime() < deadline)) {
                    BlockPos pos = bounds.at(start + inspected++);
                    String state = readOwner(pos);
                    if (state != null) values.add(new Captured(new BlockPosition(pos.getX(),pos.getY(),pos.getZ()),state));
                }
                return new CaptureBatch(List.copyOf(values),inspected);
            });
            for (Captured value : part.values()) {
                if (!first) result.append(',');
                first = false;
                BlockPosition pos = value.position();
                result.append("{\"x\":").append(pos.x()).append(",\"y\":").append(pos.y())
                        .append(",\"z\":").append(pos.z()).append(",\"state\":").append(value.state()).append('}');
            }
            reads += part.inspected();
            offset += part.inspected();
        }
        active(); // Never publish a partially captured sparse or dense region as complete.
        evidence("read-region", volume);
        return result.append(']').toString();
    }

    private String readSparseRegion(BuilderBounds bounds) {
        SparseRegion.Cursor cursor=new SparseRegion.Cursor(bounds);
        List<SparseRegion.Cell> cells=new ArrayList<>();
        long covered=0,voxelReads=0,paletteProven=0;
        while(!cursor.done()) {
            active();
            SparseRegion.Slice slice=binding.call(() -> cursor.capture(binding,QUANTUM));
            cells.addAll(slice.cells());covered+=slice.covered();voxelReads+=slice.voxelReads();paletteProven+=slice.paletteProven();
            reads+=slice.voxelReads();
        }
        active();
        if(covered!=bounds.volume())throw new IllegalStateException("Sparse capture did not cover the full region");
        // Section work is output-sensitive. Sort only explicit cells to retain the
        // dense public y,z,x ordering; omitted canonical-air cells allocate nothing.
        cells.sort(java.util.Comparator.comparingInt(SparseRegion.Cell::y).thenComparingInt(SparseRegion.Cell::z).thenComparingInt(SparseRegion.Cell::x));
        StringBuilder result=new StringBuilder("[");
        for(SparseRegion.Cell cell:cells) {
            if(result.length()>1)result.append(',');
            result.append("{\"x\":").append(cell.x()).append(",\"y\":").append(cell.y())
                    .append(",\"z\":").append(cell.z()).append(",\"state\":").append(cell.state()).append('}');
        }
        paletteProvenCells+=paletteProven;
        evidence("read-region",covered,DataCompleteness.COMPLETE,Map.of(
                "openallay_builder:coverage","full-region;exact-canonical-air-palette-proofs-and-voxel-reads"));
        return result.append(']').toString();
    }

    /** Internal terrain.js bridge. Bounds use exclusive maxY; predicates are detached IDs. */
    public String scanColumns(String requestJson) {
        active();
        captureStarted = Instant.now();
        TerrainScan.Request request = TerrainScan.Request.parse(requestJson);
        TerrainScan.Cursor cursor = new TerrainScan.Cursor(request);
        JsonArray result = new JsonArray();
        long observed = 0;
        while (!cursor.done()) {
            active();
            TerrainScan.Slice slice = binding.call(() -> cursor.capture(binding,QUANTUM));
            for (TerrainScan.Column column : slice.columns()) result.add(column.json());
            observed += slice.reads();
            reads += slice.reads();
        }
        active(); // Cancellation never publishes a partial scan as complete.
        evidence("terrain-scan",observed);
        return result.toString();
    }

    /** Internal demand-tile scan and exact headroom capture; local errors are stage-deferred. */
    public String probeColumns(String requestJson) {
        active();
        captureStarted=Instant.now();
        TerrainProbe.Request request=TerrainProbe.Request.parse(requestJson);
        JsonObject world=JsonParser.parseString(binding.context()).getAsJsonObject();
        if(request.worldMinY()!=world.get("minY").getAsInt() || request.worldMaxY()!=world.get("maxY").getAsInt())
            throw new BuilderException("invalid_bounds","Probe world height does not match the active dimension");
        TerrainProbe.Cursor cursor=new TerrainProbe.Cursor(request);
        JsonArray result=new JsonArray();
        long observed=0;
        boolean partial=false;
        while(!cursor.done()) {
            active();
            TerrainProbe.Slice slice=binding.call(() -> cursor.capture(binding,QUANTUM));
            for(TerrainProbe.Result column:slice.columns()) {result.add(column.json());partial|=column.error()!=null;}
            observed+=slice.reads();reads+=slice.reads();
        }
        active();
        evidence("terrain-probe",observed,partial?DataCompleteness.PARTIAL:DataCompleteness.COMPLETE);
        return result.toString();
    }

    public String write(int x, int y, int z, String stateJson) {
        active();
        return writeCommands(List.of(new Change(new BlockPosition(x,y,z), stateJson)));
    }

    /** Input array: [{x,y,z,state:{id,properties,blockEntity?}}]. */
    public String writeRegion(String changesJson) {
        active();
        JsonArray changes = JsonParser.parseString(changesJson).getAsJsonArray();
        List<Change> commands = new ArrayList<>(changes.size());
        for (JsonElement element : changes) {
            JsonObject change = element.getAsJsonObject();
            commands.add(new Change(new BlockPosition(BuilderBounds.integer(change,"x"), BuilderBounds.integer(change,"y"), BuilderBounds.integer(change,"z")), change.get("state").toString(),change.has("expectedBefore")?change.get("expectedBefore").toString():null));
        }
        return writeCommands(List.copyOf(commands));
    }

    private String writeCommands(List<Change> commands) {
        try { return writeCommandsActive(commands); }
        catch (RuntimeException failure) { failed(failure); throw failure; }
    }

    private String writeCommandsActive(List<Change> commands) {
        active();
        SessionLifecycle.requireWritable(state);
        if (commands.isEmpty()) return receipt(0, 0);
        captureStarted = Instant.now();
        List<Prepared> prepared = new ArrayList<>(commands.size());
        // Validate the entire command list before any mutation, in cooperative owner slices.
        for (int offset = 0; offset < commands.size();) {
            List<Change> slice = List.copyOf(commands.subList(offset, Math.min(commands.size(), offset + QUANTUM)));
            List<Prepared> part = binding.call(() -> {
                List<Prepared> result = new ArrayList<>(slice.size());
                long deadline = binding.sliceDeadline();
                for (Change command : slice) {
                    if (!result.isEmpty() && System.nanoTime() >= deadline) break;
                    BlockPos pos = pos(command.position());
                    String before = readOwner(pos);
                    ExpectedImage.requireUnchanged(command.expectedBefore(), before, command.position().toString());
                    String intended = binding.preview(pos, command.state());
                    result.add(new Prepared(command.position(), before, intended));
                }
                return List.copyOf(result);
            });
            prepared.addAll(part);
            offset += part.size();
        }
        // Last command wins for duplicate positions. One intent must represent one actual write.
        java.util.LinkedHashMap<BlockPosition,Prepared> unique = new java.util.LinkedHashMap<>();
        for (Prepared command : prepared) {
            Prepared prior = unique.get(command.position());
            unique.put(command.position(), prior == null ? command : new Prepared(command.position(), prior.before(), command.intended()));
        }
        prepared = List.copyOf(unique.values());
        long changed = 0;
        long verified = 0;
        try {
            begin();
            state = "running";
            for (int offset = 0; offset < prepared.size(); offset += WRITE_QUANTUM) {
                active();
                List<Prepared> slice = List.copyOf(prepared.subList(offset, Math.min(prepared.size(), offset + WRITE_QUANTUM)));
                operation.recordIntents(slice.stream().map(command ->
                        new OperationJournal.Intent(command.position(), spec(command.before()), spec(command.intended()))).toList());
                for (int commandOffset = 0; commandOffset < slice.size();) {
                    List<Prepared> remainingCommands = slice.subList(commandOffset,slice.size());
                    java.util.concurrent.atomic.AtomicBoolean entered = new java.util.concurrent.atomic.AtomicBoolean();
                    AppliedBatch result;
                    try {
                        result = binding.call(() -> {
                            entered.set(true);
                            List<Applied> applied = new ArrayList<>();
                            List<BlockPosition> notAttempted = new ArrayList<>();
                            RuntimeException failure = null;
                            int index = 0;
                            long deadline = binding.sliceDeadline();
                            for (; index < remainingCommands.size(); index++) {
                                if (index > 0 && System.nanoTime() >= deadline) break;
                                Prepared command = remainingCommands.get(index);
                                boolean attempted = false;
                                try {
                                    BlockPos pos = pos(command.position());
                                    String current = readOwner(pos);
                                    ExpectedImage.requireUnchanged(command.before(), current, command.position().toString());
                                    attempted = true;
                                    BuilderBackend.WriteOutcome outcome = binding.write(pos, command.intended(), current);
                                    applied.add(new Applied(command.position(), outcome.actual(), outcome.changed()));
                                    if (outcome.failure() != null) { failure = outcome.failure(); index++; break; }
                                } catch (RuntimeException next) {
                                    failure = next;
                                    if (!attempted) notAttempted.add(command.position());
                                    index++;
                                    break;
                                }
                            }
                            int processed = index;
                            if (failure != null) for (; index < remainingCommands.size(); index++) notAttempted.add(remainingCommands.get(index).position());
                            return new AppliedBatch(List.copyOf(applied),List.copyOf(notAttempted), failure,processed);
                        });
                    } catch (RuntimeException failure) {
                        if (!entered.get()) OutcomePersistence.run(() -> operation.abortIntents(remainingCommands.stream().map(Prepared::position).toList()));
                        throw failure;
                    }
                    Map<BlockPosition,BlockSpec> readbacks = new java.util.LinkedHashMap<>();
                    for (Applied item : result.applied()) readbacks.put(item.position(), spec(item.actual()));
                    for (Applied item : result.applied()) {
                        verified++;
                        if (item.changed()) { changed++; writes++; }
                    }
                    OutcomePersistence.run(() -> operation.resolve(readbacks,result.notAttempted()));
                    if (result.failure() != null) throw result.failure();
                    commandOffset += result.processed();
                }
            }
            return receipt(changed, verified);
        } catch (IOException failure) {
            failed(io(failure));
            throw io(failure);
        } catch (RuntimeException failure) {
            failed(failure);
            throw failure;
        } finally {
            if (verified > 0) evidence("write-readback",verified);
        }
    }

    public String transformState(String stateJson, int degrees, String mirror) {
        active();
        String result = binding.call(() -> binding.transform(stateJson, degrees, mirror));
        frame.evidence(new EvidenceMetadata(DataAuthority.INTEGRATION_API, DataCompleteness.COMPLETE,
                Instant.now(), "openallay_builder:derived-native-state", "openallay:builder", "26.2", frame.loader(),
                Map.of("openallay_builder:coverage","input-only", "openallay_builder:operation","native-block-state-transform")));
        return result;
    }

    /** Recomputes native fence/wall/pane/stair states for the supplied inclusive region. */
    public String updateConnections(String boundsJson) {
        try { return updateConnectionsActive(boundsJson); }
        catch (RuntimeException failure) { failed(failure); throw failure; }
    }

    private String updateConnectionsActive(String boundsJson) {
        active();
        captureStarted=Instant.now();
        BuilderBounds bounds=connectionBounds(boundsJson);
        ConnectionRepair.Cursor cursor=new ConnectionRepair.Cursor(bounds);
        long changed=0,covered=0;
        while(!cursor.done()) {
            active();
            ConnectionRepair.Slice slice=binding.call(() -> cursor.capture(binding,QUANTUM));
            covered+=slice.covered();reads+=slice.voxelReads();paletteProvenCells+=slice.paletteProven();
            if(!slice.changes().isEmpty()) {
                List<Change> changes=slice.changes().stream().map(value ->
                        new Change(new BlockPosition(value.x(),value.y(),value.z()),value.intended(),value.before())).toList();
                changed+=JsonParser.parseString(writeCommands(changes)).getAsJsonObject().get("changed").getAsLong();
            }
        }
        active();
        evidence("connection-repair",covered,DataCompleteness.COMPLETE,
                Map.of("openallay_builder:coverage","native-shapes;explicit-writes-only;no-physics-broadcast"));
        JsonObject result=JsonParser.parseString(receipt(changed,covered)).getAsJsonObject();
        result.addProperty("phase","connection-shapes");
        return result.toString();
    }

    /** Explicit normal neighbor/comparator physics for the requested region plus one-block halo. */
    public String syncPhysics(String boundsJson) {
        try {
            active();
            captureStarted=Instant.now();
            BuilderBounds bounds=connectionBounds(boundsJson);
            notifyAndReadback(bounds);
            active();
            evidence("physics-sync",bounds.volume(),DataCompleteness.COMPLETE,
                    Map.of("openallay_builder:journal_coverage","explicit-writes-only","openallay_builder:coverage","native-neighbor-and-comparator-notifications"));
            JsonObject result=JsonParser.parseString(receipt(0,bounds.volume())).getAsJsonObject();
            result.addProperty("phase","physics-sync");
            return result.toString();
        } catch(RuntimeException failure){failed(failure);throw failure;}
    }

    private BuilderBounds connectionBounds(String boundsJson) {
        BuilderBounds requested=BuilderBounds.parse(boundsJson);
        JsonObject world=JsonParser.parseString(binding.context()).getAsJsonObject();
        try {
            return new BuilderBounds(Math.subtractExact(requested.minX(),1),
                    Math.max(world.get("minY").getAsInt(),Math.subtractExact(requested.minY(),1)),
                    Math.subtractExact(requested.minZ(),1),Math.addExact(requested.maxX(),1),
                    Math.min(world.get("maxY").getAsInt()-1,Math.addExact(requested.maxY(),1)),Math.addExact(requested.maxZ(),1));
        } catch(ArithmeticException invalid){throw new BuilderException("invalid_bounds","Connection halo exceeds coordinate range",invalid);}
    }

    private void notifyAndReadback(BuilderBounds bounds) {
        long volume=bounds.volume();
        int batchLimit=WRITE_QUANTUM;
        for(long offset=0;offset<volume;) {
            long start=offset;
            int size=(int)Math.min(batchLimit,volume-offset);
            List<Prepared> captures=binding.call(()->{
                List<Prepared> values=new ArrayList<>();
                long deadline=binding.sliceDeadline();
                for(int i=0;i<size;i++) {
                    if(i>0 && System.nanoTime()>=deadline) break;
                    BlockPos pos=bounds.at(start+i);
                    String before=readOwner(pos);
                    values.add(new Prepared(new BlockPosition(pos.getX(),pos.getY(),pos.getZ()),before,before));
                }
                return List.copyOf(values);
            });
            int count=binding.call(()->{
                // First compare the entire admitted prefix. No callback may run
                // before its before-images have passed optimistic validation.
                long deadline=binding.sliceDeadline();
                int admitted=0;
                for(Prepared value:captures) {
                    if(admitted>0 && System.nanoTime()>=deadline) break;
                    ExpectedImage.requireUnchanged(value.before(),readOwner(pos(value.position())),value.position().toString());
                    admitted++;
                }
                int processed=0;
                for(int i=0;i<admitted;i++) {
                    if(processed>0 && System.nanoTime()>=deadline) break;
                    binding.notifyNeighbours(pos(captures.get(i).position()));
                    processed++;
                }
                // Started callbacks and their immediate readbacks form one bounded
                // native completion unit; never discard an in-flight observation.
                for(int i=0;i<processed;i++) readOwner(pos(captures.get(i).position()));
                return processed;
            });
            offset+=count;
            // Earlier callbacks may legitimately change an unnotified cell. Recapture
            // that tail in the next declared non-atomic slice, not as an external race.
            // Adapt the work maximum to avoid repeatedly capturing a large unused tail.
            batchLimit=count<captures.size()?count:Math.min(WRITE_QUANTUM,count*2);
        }
        // Native physics can cascade outside this region and across later ticks. It is not
        // attributed as our direct block edit; resulting postimage changes cause undo conflicts.
    }

    public void saveTemplate(String name, String json) {
        active();
        try { templates.save(name, json); artifactEvidence("template-save", 1); }
        catch (IOException failure) { throw io(failure); }
    }
    public String loadTemplate(String name) {
        active();
        try { String result = templates.load(name).toString(); artifactEvidence("template-load", 1); return result; }
        catch (IOException failure) { throw io(failure); }
    }
    public String listTemplates() {
        active();
        try { JsonArray names = new JsonArray(); templates.list().forEach(names::add); artifactEvidence("template-list", names.size()); return names.toString(); }
        catch (IOException failure) { throw io(failure); }
    }

    public String undo() {
        active();
        if (operation == null && lastOperation == null) throw new BuilderException("operation_required", "No operation is available for undo; supply an operation ID");
        return undo(operation == null ? lastOperation : operation.id());
    }
    public String undo(String operationId) {
        active();
        long restored = 0;
        captureStarted = Instant.now();
        try {
            completeOperation();
            OperationJournal.Snapshot previous = journal.load(operationId);
            if (!previous.worldId().equals(binding.worldId()) || !previous.dimension().equals(binding.dimension()))
                throw new BuilderException("wrong_world", "Undo journal belongs to another world or dimension");
            operation = journal.begin(binding.worldId(), binding.dimension(), "Undo " + operationId);
            state = "running";
            detail = "";
            JsonArray conflicts = new JsonArray();
            JsonArray uncertain = new JsonArray();
            List<OperationJournal.Entry> entries = previous.reverseEntries();
            for (int offset = 0; offset < entries.size();) {
                active();
                List<OperationJournal.Entry> slice = List.copyOf(entries.subList(offset,Math.min(entries.size(),offset+WRITE_QUANTUM)));
                UndoPrepared prepared = binding.call(() -> {
                    List<Prepared> commands = new ArrayList<>();
                    List<BlockPosition> blocked = new ArrayList<>(), pending = new ArrayList<>();
                    long deadline = binding.sliceDeadline();
                    int inspected = 0;
                    for (OperationJournal.Entry entry : slice) {
                        if (inspected > 0 && System.nanoTime() >= deadline) break;
                        inspected++;
                        if (entry.pending()) { pending.add(entry.position()); continue; }
                        BlockPos pos = pos(entry.position());
                        String current = readOwner(pos);
                        if (!entry.check(spec(current)).matches()) { blocked.add(entry.position()); continue; }
                        commands.add(new Prepared(entry.position(),current,binding.preview(pos,entry.before().toJsonString())));
                    }
                    return new UndoPrepared(List.copyOf(commands),List.copyOf(blocked),List.copyOf(pending),inspected);
                });
                offset += prepared.inspected();
                prepared.conflicts().forEach(position -> conflicts.add(position(pos(position))));
                prepared.uncertain().forEach(position -> uncertain.add(position(pos(position))));
                if (prepared.commands().isEmpty()) continue;
                operation.recordIntents(prepared.commands().stream().map(command ->
                        new OperationJournal.Intent(command.position(),spec(command.before()),spec(command.intended()))).toList());
                for (int commandOffset = 0; commandOffset < prepared.commands().size();) {
                    List<Prepared> commands = prepared.commands().subList(commandOffset,prepared.commands().size());
                    java.util.concurrent.atomic.AtomicBoolean entered = new java.util.concurrent.atomic.AtomicBoolean();
                    UndoApplied applied;
                    try {
                        applied = binding.call(() -> {
                            entered.set(true);
                            List<Applied> outcomes = new ArrayList<>();
                            List<BlockPosition> unstarted = new ArrayList<>(), races = new ArrayList<>();
                            RuntimeException failure = null;
                            int index = 0;
                            long deadline = binding.sliceDeadline();
                            for (; index < commands.size(); index++) {
                                if (index > 0 && System.nanoTime() >= deadline) break;
                                Prepared command = commands.get(index);
                                boolean attempted = false;
                                try {
                                    BlockPos pos = pos(command.position());
                                    String current = readOwner(pos);
                                    if (!spec(command.before()).equals(spec(current))) {
                                        unstarted.add(command.position()); races.add(command.position()); continue;
                                    }
                                    attempted = true;
                                    BuilderBackend.WriteOutcome outcome = binding.write(pos,command.intended(),current);
                                    outcomes.add(new Applied(command.position(),outcome.actual(),outcome.changed()));
                                    if (outcome.failure() != null) { failure = outcome.failure(); index++; break; }
                                } catch (RuntimeException next) {
                                    failure = next;
                                    if (!attempted) unstarted.add(command.position());
                                    index++; break;
                                }
                            }
                            int processed = index;
                            // On a cooperative yield, remaining durable intents stay pending.
                            // On failure, explicitly withdraw only commands proven unstarted.
                            if (failure != null) for (; index < commands.size(); index++) unstarted.add(commands.get(index).position());
                            return new UndoApplied(List.copyOf(outcomes),List.copyOf(unstarted),List.copyOf(races),failure,processed);
                        });
                    } catch (RuntimeException failure) {
                        if (!entered.get()) OutcomePersistence.run(() -> operation.abortIntents(commands.stream().map(Prepared::position).toList()));
                        throw failure;
                    }
                    Map<BlockPosition,BlockSpec> actual = new java.util.LinkedHashMap<>();
                    for (Applied item : applied.applied()) actual.put(item.position(),spec(item.actual()));
                    applied.conflicts().forEach(position -> conflicts.add(position(pos(position))));
                    for (Applied item : applied.applied()) { restored++; if (item.changed()) writes++; }
                    OutcomePersistence.run(() -> operation.resolve(actual,applied.unstarted()));
                    if (applied.failure() != null) throw applied.failure();
                    commandOffset += applied.processed();
                }
            }
            completeOperation();
            JsonObject result = new JsonObject(); result.addProperty("restored",restored); result.add("conflicts",conflicts); result.add("uncertain",uncertain); result.addProperty("operationId",lastOperation);
            return result.toString();
        } catch (IOException failure) { failed(io(failure)); throw io(failure); }
        catch (RuntimeException failure) { failed(failure); throw failure; }
        finally { if (restored > 0) evidence("undo-readback",restored); }
    }

    public String listOperations() {
        active();
        try {
            JsonArray result = new JsonArray();
            java.util.Optional<String> worldId = binding.existingWorldId();
            if (worldId.isEmpty()) {
                artifactEvidence("operation-list", 0);
                return result.toString();
            }
            for (OperationJournal.Snapshot item : journal.list()) {
                if (!item.worldId().equals(worldId.orElseThrow()) || !item.dimension().equals(binding.dimension())) continue;
                JsonObject record = new JsonObject(); record.addProperty("id", item.id()); record.addProperty("label", item.label()); record.addProperty("status", item.status().name().toLowerCase()); record.addProperty("entries", item.entries().size()); result.add(record);
            }
            artifactEvidence("operation-list", result.size());
            return result.toString();
        } catch (IOException failure) { throw io(failure); }
    }

    public String finish() {
        active();
        SessionLifecycle.requireCompletable(state);
        try { completeOperation(); state = "completed"; return status(); }
        catch (IOException failure) { failed(io(failure)); throw io(failure); }
    }

    public String status() {
        if (Thread.currentThread() != worker) throw new BuilderException("wrong_worker", "Builder session belongs to its invocation worker");
        JsonObject result = new JsonObject(); result.addProperty("state",state); result.addProperty("reads",reads); result.addProperty("writes",writes); result.addProperty("detail",detail);
        result.addProperty("nativeDispatches",binding.dispatches()-dispatchStart);
        result.addProperty("paletteProvenCells",paletteProvenCells);
        String id = operation == null ? lastOperation : operation.id(); if (id != null) result.addProperty("operationId", id);
        return result.toString();
    }
    public void cancel() { active(); cancelled = true; state = SessionLifecycle.cancelled(writes, operation != null && operation.hasEntries()); terminate(OperationJournal.Status.CANCELLED, "Explicit cancellation; already applied world changes remain"); }
    @Override public void close() {
        if (closed) return;
        bridge.checkWorker();
        if (!state.startsWith("failed") && !state.startsWith("cancelled")) {
            try { completeOperation(); state="completed"; }
            catch(IOException failure) { failed(io(failure)); throw io(failure); }
        }
        closed=true;
    }

    void closeAfterInvocation() {
        if (closed) return;
        if (Thread.currentThread() != worker) throw new BuilderException("wrong_worker", "Builder session close must run on its invocation worker");
        try {
            if (state.startsWith("failed") || state.startsWith("cancelled") || state.equals("completed")) {
                // Never relabel an explicit terminal outcome during scope cleanup.
            } else if (frame.completedSuccessfully()) {
                completeOperation(); state = "completed";
            } else if (cancelled) {
                state = SessionLifecycle.cancelled(writes, operation != null && operation.hasEntries());
                terminate(OperationJournal.Status.CANCELLED, "Explicit cancellation; no automatic rollback");
            } else {
                state = "interrupted";
                terminate(OperationJournal.Status.INTERRUPTED, "Invocation ended without successful completion; no automatic rollback");
            }
        } catch (IOException failure) { throw io(failure); }
        finally { closed = true; }
    }

    private void begin() throws IOException { if (operation == null) operation = journal.begin(binding.worldId(),binding.dimension(),label); }
    private void completeOperation() throws IOException {
        if (operation != null) { OutcomePersistence.run(() -> operation.finish(OperationJournal.Status.COMPLETED, "All journaled writes have server readback")); lastOperation = operation.id(); operation = null; }
    }
    private void failed(RuntimeException failure) {
        detail = failure.getMessage() == null ? failure.getClass().getSimpleName() : failure.getMessage();
        boolean revoked = frame.cancelled() || cancelled || Thread.currentThread().isInterrupted();
        state = SessionLifecycle.failed(revoked, writes, operation != null && operation.hasEntries());
        terminate(revoked ? OperationJournal.Status.CANCELLED : OperationJournal.Status.FAILED, detail);
    }
    private void terminate(OperationJournal.Status terminal, String message) {
        if (operation == null) return;
        try { OutcomePersistence.run(() -> operation.finish(terminal,message)); lastOperation = operation.id(); operation = null; }
        catch (IOException failure) { throw io(failure); }
    }
    private void evidence(String action, long count) { evidence(action,count,DataCompleteness.COMPLETE); }
    private void evidence(String action, long count, DataCompleteness completeness) { evidence(action,count,completeness,Map.of()); }
    private void evidence(String action, long count, DataCompleteness completeness,Map<String,String> extra) {
        boolean sliced = action.equals("terrain-probe") || action.equals("read-region") || action.equals("read-positions") || action.equals("terrain-scan") || action.equals("physics-sync") || action.equals("write-readback") || action.equals("undo-readback") || action.equals("connection-repair");
        Instant capturedAt=Instant.now();
        Map<String,String> details=new java.util.LinkedHashMap<>();
        details.put("openallay_builder:topology","integrated-server"); details.put("openallay_builder:dimension",binding.dimension());
        details.put("openallay_builder:count",Long.toString(count)); details.put("openallay_builder:consistency",sliced?"multi-slice-non-atomic":"owner-action");
        if(sliced) { details.put("openallay_builder:capture_start",captureStarted.toString()); details.put("openallay_builder:capture_end",capturedAt.toString()); }
        if(action.equals("connection-repair")) details.put("openallay_builder:journal_coverage","explicit-writes-only");
        if(action.equals("terrain-probe")) details.put("openallay_builder:coverage","physical-successful-reads;column-local-errors-deferred");
        details.putAll(extra);
        frame.evidence(new EvidenceMetadata(DataAuthority.SERVER_AUTHORITATIVE, completeness,
                capturedAt, "openallay_builder:"+action, "openallay:builder", "26.2", frame.loader(),details));
    }
    private void artifactEvidence(String action, long count) {
        frame.evidence(new EvidenceMetadata(DataAuthority.INTEGRATION_API, DataCompleteness.COMPLETE,
                Instant.now(), "openallay_builder:"+action, "openallay:builder", "26.2", frame.loader(),
                Map.of("openallay_builder:storage","extension-artifacts", "openallay_builder:count",Long.toString(count))));
    }
    private String receipt(long changed,long verified) {
        JsonObject result = new JsonObject(); result.addProperty("changed",changed); result.addProperty("verified",verified);
        if (operation != null) result.addProperty("operationId",operation.id()); return result.toString();
    }
    private static JsonObject position(BlockPos pos) { JsonObject result = new JsonObject(); result.addProperty("x",pos.getX()); result.addProperty("y",pos.getY()); result.addProperty("z",pos.getZ()); return result; }
    private static BlockPos pos(BlockPosition pos) { return new BlockPos(pos.x(),pos.y(),pos.z()); }
    private static BlockSpec spec(String value) { return BlockSpec.fromJson(value); }
    private static BuilderException io(IOException failure) {
        return new BuilderException("artifact_io", "Builder artifact/journal persistence failed", failure);
    }
    private record Captured(BlockPosition position,String state) {}
    private record CaptureBatch(List<Captured> values,int inspected) {}
    private record Change(BlockPosition position,String state,String expectedBefore) {
        Change(BlockPosition position,String state) { this(position,state,null); }
    }
    private record Prepared(BlockPosition position,String before,String intended) {}
    private record Applied(BlockPosition position,String actual,boolean changed) {}
    private record AppliedBatch(List<Applied> applied,List<BlockPosition> notAttempted, RuntimeException failure,int processed) {}
    private record UndoPrepared(List<Prepared> commands,List<BlockPosition> conflicts,List<BlockPosition> uncertain,int inspected) {}
    private record UndoApplied(List<Applied> applied,List<BlockPosition> unstarted,List<BlockPosition> conflicts,RuntimeException failure,int processed) {}
}
