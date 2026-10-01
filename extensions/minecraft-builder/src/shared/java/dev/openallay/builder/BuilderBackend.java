package dev.openallay.builder;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import net.minecraft.core.BlockPos;

/** Small native seam: production dispatches to game owners; tests use detached in-memory states. */
interface BuilderBackend {
    record WriteOutcome(String actual, boolean changed, RuntimeException failure) {}
    <T> T call(Callable<T> action);
    /** Owner slice deadline; tests and detached backends use only deterministic work limits. */
    default long sliceDeadline() { return Long.MAX_VALUE; }
    default long dispatches() { return 0; }
    /** True only when this captured native backend is running on either game owner. */
    default boolean isOwnerThread() { return false; }
    void validatePosition(BlockPos position);
    String read(BlockPos position);
    /** Sparse capture omits only exact canonical air, never cave/void/custom air. */
    default String readNonAir(BlockPos position) {
        String state = read(position);
        var block = dev.openallay.builder.storage.BlockSpec.fromJson(state);
        return block.id().equals("minecraft:air") && block.properties().isEmpty() && block.blockEntity() == null ? null : state;
    }
    /** Exact palette proof for a clipped single-section tile. No proof may cross owner actions. */
    default boolean canonicalAir(BuilderBounds bounds) { return false; }
    /** Owner-only detached state read; terrain never copies block-entity payloads. */
    default dev.openallay.builder.storage.BlockSpec terrainState(BlockPos position) {
        return dev.openallay.builder.storage.BlockSpec.fromJson(read(position));
    }
    /** Inclusive safe scan start; detached backends make no air/heightmap assumptions. */
    default int terrainTop(int x, int z, int minY, int maxY) {
        validatePosition(new BlockPos(x,minY,z));
        validatePosition(new BlockPos(x,maxY-1,z));
        return maxY-1;
    }
    String preview(BlockPos position, String state);
    WriteOutcome write(BlockPos position, String state);
    /** Exact optimistic read from this same owner action; production reuses it for failure accounting. */
    default WriteOutcome write(BlockPos position, String state, String before) { return write(position,state); }
    String transform(String state, int degrees, String mirror);
    String repairedState(BlockPos position);
    record RepairOutcome(String before,String intended) {}
    /** Detached repair result; full before-image is serialized only for an actual native shape change. */
    default RepairOutcome repair(BlockPos position) {
        String before=read(position),updated=repairedState(position);
        return updated==null?null:new RepairOutcome(before,updated);
    }
    void notifyNeighbours(BlockPos position);
    String context();
    String dimension();
    /** Create/read the journal world identity only for an authorized native world action. */
    String worldId();
    /** Read an existing identity without creating Minecraft SavedData. */
    java.util.Optional<String> existingWorldId();
    Path artifacts();
}
