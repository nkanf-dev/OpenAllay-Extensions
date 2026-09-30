package dev.openallay.builder;

import java.nio.file.Path;
import java.util.concurrent.Callable;
import net.minecraft.core.BlockPos;

/** Small native seam: production dispatches to game owners; tests use detached in-memory states. */
interface BuilderBackend {
    record WriteOutcome(String actual, boolean changed, RuntimeException failure) {}
    <T> T call(Callable<T> action);
    void validatePosition(BlockPos position);
    String read(BlockPos position);
    String preview(BlockPos position, String state);
    WriteOutcome write(BlockPos position, String state);
    String transform(String state, int degrees, String mirror);
    String repairedState(BlockPos position);
    void notifyNeighbours(BlockPos position);
    String context();
    String dimension();
    String worldId();
    Path artifacts();
}
