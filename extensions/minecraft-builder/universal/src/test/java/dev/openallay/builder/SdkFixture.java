package dev.openallay.builder;

import dev.openallay.api.extension.ExtensionEnvironment;
import dev.openallay.api.extension.ExtensionEvidence;
import dev.openallay.api.extension.ExtensionException;
import dev.openallay.api.extension.ExtensionHost;
import dev.openallay.api.extension.ExtensionInvocation;
import dev.openallay.api.extension.MinecraftWorldAccess;
import dev.openallay.api.extension.WorldSession;
import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.BlockSpec;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;

/** A detached SDK contract fixture, not a substitute for a Minecraft native adapter. */
final class SdkFixture {
    private SdkFixture() {}

    static final class Invocation implements ExtensionInvocation {
        final List<Runnable> cancellationListeners = new ArrayList<Runnable>();
        final List<ExtensionEvidence> evidence = new ArrayList<ExtensionEvidence>();
        final UUID actor = UUID.randomUUID();
        final Instant captured = Instant.now();
        boolean cancelled;
        boolean successful;
        @Override public String extensionId() { return "openallay:builder"; }
        @Override public String correlationId() { return "builder-sdk-fixture"; }
        @Override public Instant capturedAt() { return captured; }
        @Override public CallerKind callerKind() { return CallerKind.PLAYER; }
        @Override public UUID callerUuid() { return actor; }
        @Override public Optional<String> playerDimension() { return Optional.of("minecraft:overworld"); }
        @Override public void requireActive() {
            if (cancelled) throw new ExtensionException("cancelled", "Fixture invocation cancelled");
        }
        @Override public boolean isCancelled() { return cancelled; }
        @Override public void onCancel(Runnable listener) {
            if (cancelled) listener.run(); else cancellationListeners.add(listener);
        }
        void cancel() {
            if (cancelled) return;
            cancelled = true;
            for (Runnable listener : new ArrayList<Runnable>(cancellationListeners)) listener.run();
        }
        @Override public boolean completedSuccessfully() { return successful; }
        @Override public void recordEvidence(ExtensionEvidence value) { evidence.add(value); }
    }

    static final class Host implements ExtensionHost {
        final World world;
        int accessRequests;
        int opens;
        ExtensionInvocation openedInvocation;
        RuntimeException openFailure;
        boolean accessMissing;
        boolean returnsNullWorld;
        Host(World world) { this.world = world; }
        @Override public ExtensionEnvironment environment() {
            return new ExtensionEnvironment("fabric", "1.20.1", "0.4.1", FixtureValues.set("0.4.0"),
                    8, FixtureValues.set("minecraft:world-access"));
        }
        @Override public MinecraftWorldAccess minecraftWorldAccess() {
            accessRequests++;
            if (accessMissing) return null;
            return invocation -> {
                opens++;
                invocation.requireActive();
                openedInvocation = invocation;
                if (openFailure != null) throw openFailure;
                if (returnsNullWorld) return null;
                if (world == null) throw new AssertionError("Fixture has no detached world");
                return world;
            };
        }
    }

    static class World implements WorldSession {
        final Path path;
        final Invocation invocation;
        final Map<BlockPosition, String> blocks = new HashMap<BlockPosition, String>();
        int calls;
        int reads;
        int previews;
        int writes;
        int notifications;
        int closes;
        int worldIdentityCreates;
        int worldIdentityReads;
        int validations;
        BlockPosition lastPosition;
        BuilderBounds lastBounds;
        String lastState;
        String lastBefore;
        String lastTransformState;
        int lastRotation;
        String lastMirror;
        String afterWriteState;
        String repairBefore;
        String repairIntended;
        RuntimeException writeFailure;
        RuntimeException callFailure;
        boolean missingReadback;
        boolean identityExists;
        boolean closed;
        boolean owner;
        long deadline = Long.MAX_VALUE;
        World(Path path, Invocation invocation) { this.path = path; this.invocation = invocation; }
        void active() {
            invocation.requireActive();
            if (closed) throw new ExtensionException("session_closed", "Fixture world session closed");
        }
        BlockPosition position(int x, int y, int z) {
            lastPosition = new BlockPosition(x, y, z);
            return lastPosition;
        }
        @Override public <T> T call(Callable<T> action) {
            active();
            calls++;
            if (callFailure != null) throw callFailure;
            owner = true;
            try { return action.call(); }
            catch (RuntimeException failure) { throw failure; }
            catch (Exception failure) { throw new RuntimeException(failure); }
            finally { owner = false; }
        }
        @Override public long sliceDeadline() { return deadline; }
        @Override public long dispatches() { return calls; }
        @Override public boolean isOwnerThread() { return owner; }
        @Override public void validatePosition(int x, int y, int z) {
            active(); validations++; position(x, y, z);
            if (y < -64 || y >= 320) throw new ExtensionException("invalid_coordinate", "Fixture height exceeded");
        }
        @Override public String read(int x, int y, int z) {
            active(); reads++;
            return blocks.getOrDefault(position(x, y, z), BuilderSessionTest.AIR);
        }
        @Override public String readNonAir(int x, int y, int z) {
            String state = read(x, y, z);
            BlockSpec image = BlockSpec.fromJson(state);
            return image.id().equals("minecraft:air") && image.properties().isEmpty() && image.blockEntity() == null
                    ? null : state;
        }
        @Override public boolean canonicalAir(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            active(); lastBounds = new BuilderBounds(minX, minY, minZ, maxX, maxY, maxZ);
            for (Map.Entry<BlockPosition, String> entry : blocks.entrySet()) {
                BlockPosition p = entry.getKey();
                if (p.x() >= minX && p.x() <= maxX && p.y() >= minY && p.y() <= maxY
                        && p.z() >= minZ && p.z() <= maxZ && !BuilderSessionTest.AIR.equals(entry.getValue())) return false;
            }
            return true;
        }
        @Override public String terrainState(int x, int y, int z) {
            BlockSpec image = BlockSpec.fromJson(read(x, y, z));
            return new BlockSpec(image.id(), image.properties()).toJsonString();
        }
        @Override public int terrainTop(int x, int z, int minY, int maxY) {
            active(); return maxY - 1;
        }
        @Override public String preview(int x, int y, int z, String state) {
            active(); previews++; position(x, y, z); lastState = state;
            return state;
        }
        @Override public WriteOutcome write(int x, int y, int z, String state) {
            return apply(x, y, z, state, null);
        }
        @Override public WriteOutcome write(int x, int y, int z, String state, String before) {
            lastBefore = before;
            return apply(x, y, z, state, before);
        }
        private WriteOutcome apply(int x, int y, int z, String state, String before) {
            active();
            BlockPosition p = position(x, y, z);
            String previous = blocks.getOrDefault(p, BuilderSessionTest.AIR);
            if (before != null && !before.equals(previous))
                return new WriteOutcome(previous, false, new ExtensionException("concurrent_edit", "Fixture CAS conflict"));
            writes++; lastState = state;
            String actual = afterWriteState == null ? state : afterWriteState;
            blocks.put(p, actual);
            return new WriteOutcome(missingReadback ? null : actual, !previous.equals(actual), writeFailure);
        }
        @Override public String transform(String state, int degrees, String mirror) {
            active(); lastTransformState = state; lastRotation = degrees; lastMirror = mirror; return state;
        }
        @Override public String repairedState(int x, int y, int z) {
            active(); position(x, y, z); return repairIntended;
        }
        @Override public RepairOutcome repair(int x, int y, int z) {
            active(); position(x, y, z);
            return repairIntended == null ? null : new RepairOutcome(repairBefore, repairIntended);
        }
        @Override public void notifyNeighbours(int x, int y, int z) {
            active(); notifications++; position(x, y, z);
        }
        @Override public String context() {
            active(); return "{\"minY\":-64,\"maxY\":320,\"version\":\"1.20.1\"}";
        }
        @Override public String dimension() { active(); return "minecraft:overworld"; }
        @Override public String worldId() {
            active();
            if (!identityExists) { identityExists = true; worldIdentityCreates++; }
            return "sdk-fixture-world";
        }
        @Override public Optional<String> existingWorldId() {
            active(); worldIdentityReads++;
            return identityExists ? Optional.of("sdk-fixture-world") : Optional.<String>empty();
        }
        @Override public Path artifacts() { return path; }
        @Override public void close() { closes++; closed = true; }
    }
}
