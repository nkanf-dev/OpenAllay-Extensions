package dev.openallay.builder;

import dev.openallay.api.extension.ExtensionException;
import dev.openallay.api.extension.WorldSession;
import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.BlockSpec;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/** SDK-only facade. Coordinates and state JSON cross the native boundary; game objects never do. */
final class WorldSessionBackend implements BuilderBackend {
    private final WorldSession world;
    private final AtomicBoolean closed = new AtomicBoolean();
    WorldSessionBackend(WorldSession world) { this.world = java.util.Objects.requireNonNull(world, "world"); }

    @Override public <T> T call(Callable<T> action) {
        try { return world.call(action); }
        catch (ExtensionException failure) { throw domainFailure(failure); }
    }
    @Override public long sliceDeadline() { return world.sliceDeadline(); }
    @Override public long dispatches() { return world.dispatches(); }
    @Override public boolean isOwnerThread() { return world.isOwnerThread(); }
    @Override public void validatePosition(BlockPosition p) { try { world.validatePosition(p.x(), p.y(), p.z()); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public String read(BlockPosition p) { try { return world.read(p.x(), p.y(), p.z()); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public String readNonAir(BlockPosition p) { try { return world.readNonAir(p.x(), p.y(), p.z()); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public boolean canonicalAir(BuilderBounds b) {
        try { return world.canonicalAir(b.minX(), b.minY(), b.minZ(), b.maxX(), b.maxY(), b.maxZ()); }
        catch (ExtensionException failure) { throw domainFailure(failure); }
    }
    @Override public BlockSpec terrainState(BlockPosition p) { try { return BlockSpec.fromJson(world.terrainState(p.x(), p.y(), p.z())); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public int terrainTop(int x, int z, int minY, int maxY) { try { return world.terrainTop(x, z, minY, maxY); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public String preview(BlockPosition p, String state) { try { return world.preview(p.x(), p.y(), p.z(), state); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public WriteOutcome write(BlockPosition p, String state) { try { return outcome(world.write(p.x(), p.y(), p.z(), state)); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public WriteOutcome write(BlockPosition p, String state, String before) { try { return outcome(world.write(p.x(), p.y(), p.z(), state, before)); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    private static WriteOutcome outcome(WorldSession.WriteOutcome value) {
        // A failed native write may still have changed the world. Keep the actual readback.
        return new WriteOutcome(value.actual(), value.changed(), domainFailure(value.failure()));
    }
    @Override public String transform(String state, int degrees, String mirror) { try { return world.transform(state, degrees, mirror); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public String repairedState(BlockPosition p) { try { return world.repairedState(p.x(), p.y(), p.z()); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public RepairOutcome repair(BlockPosition p) {
        try {
            WorldSession.RepairOutcome value = world.repair(p.x(), p.y(), p.z());
            return value == null ? null : new RepairOutcome(value.before(), value.intended());
        } catch (ExtensionException failure) { throw domainFailure(failure); }
    }
    @Override public void notifyNeighbours(BlockPosition p) { try { world.notifyNeighbours(p.x(), p.y(), p.z()); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public String context() {
        try { return world.context(); } catch (ExtensionException failure) { throw domainFailure(failure); }
    }
    @Override public String dimension() { try { return world.dimension(); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public String worldId() { try { return world.worldId(); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public Optional<String> existingWorldId() { try { return world.existingWorldId(); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public Path artifacts() { try { return world.artifacts(); } catch (ExtensionException failure) { throw domainFailure(failure); } }
    @Override public void close() { if (closed.compareAndSet(false, true)) world.close(); }
    private static RuntimeException domainFailure(RuntimeException failure) {
        if (!(failure instanceof ExtensionException)) return failure;
        ExtensionException sdk = (ExtensionException) failure;
        return new BuilderException(sdk.code(), sdk.summary(), sdk);
    }
}
