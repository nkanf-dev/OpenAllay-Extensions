package dev.openallay.builder;

import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.BlockSpec;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;

/** Detached domain seam. Production delegates every native operation to the public WorldSession. */
interface BuilderBackend extends AutoCloseable {
    final class WriteOutcome {
        private final String actual;
        private final boolean changed;
        private final RuntimeException failure;
        WriteOutcome(String actual, boolean changed, RuntimeException failure) {
            this.actual = actual; this.changed = changed; this.failure = failure;
        }
        String actual() { return actual; }
        boolean changed() { return changed; }
        RuntimeException failure() { return failure; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof WriteOutcome)) return false;
            WriteOutcome that = (WriteOutcome) other;
            return changed == that.changed && java.util.Objects.equals(actual, that.actual)
                    && java.util.Objects.equals(failure, that.failure);
        }
        @Override public int hashCode() { return 31 * (31 * java.util.Objects.hashCode(actual) + Boolean.hashCode(changed)) + java.util.Objects.hashCode(failure); }
        @Override public String toString() { return "WriteOutcome[actual=" + actual + ", changed=" + changed + ", failure=" + failure + "]"; }
    }
    final class RepairOutcome {
        private final String before;
        private final String intended;
        RepairOutcome(String before, String intended) { this.before = before; this.intended = intended; }
        String before() { return before; }
        String intended() { return intended; }
        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof RepairOutcome)) return false;
            RepairOutcome that = (RepairOutcome) other;
            return java.util.Objects.equals(before, that.before) && java.util.Objects.equals(intended, that.intended);
        }
        @Override public int hashCode() { return 31 * java.util.Objects.hashCode(before) + java.util.Objects.hashCode(intended); }
        @Override public String toString() { return "RepairOutcome[before=" + before + ", intended=" + intended + "]"; }
    }
    <T> T call(Callable<T> action);
    default long sliceDeadline() { return Long.MAX_VALUE; }
    default long dispatches() { return 0; }
    default boolean isOwnerThread() { return false; }
    void validatePosition(BlockPosition position);
    String read(BlockPosition position);
    default String readNonAir(BlockPosition position) {
        String state = read(position);
        BlockSpec block = BlockSpec.fromJson(state);
        return block.id().equals("minecraft:air") && block.properties().isEmpty() && block.blockEntity() == null ? null : state;
    }
    default boolean canonicalAir(BuilderBounds bounds) { return false; }
    default BlockSpec terrainState(BlockPosition position) { return BlockSpec.fromJson(read(position)); }
    default int terrainTop(int x, int z, int minY, int maxY) {
        validatePosition(new BlockPosition(x, minY, z));
        validatePosition(new BlockPosition(x, maxY - 1, z));
        return maxY - 1;
    }
    String preview(BlockPosition position, String state);
    WriteOutcome write(BlockPosition position, String state);
    default WriteOutcome write(BlockPosition position, String state, String before) { return write(position, state); }
    String transform(String state, int degrees, String mirror);
    String repairedState(BlockPosition position);
    default RepairOutcome repair(BlockPosition position) {
        String before = read(position), updated = repairedState(position);
        return updated == null ? null : new RepairOutcome(before, updated);
    }
    void notifyNeighbours(BlockPosition position);
    String context();
    String dimension();
    String worldId();
    Optional<String> existingWorldId();
    Path artifacts();
    @Override default void close() {}
}
