package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import dev.openallay.api.extension.ExtensionException;
import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.BlockSpec;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class WorldSessionBackendTest {
    @TempDir Path directory;

    @Test void changedWriteFailureRetainsActualDetachedReadbackAndFailureCode() {
        SdkFixture.World world = new SdkFixture.World(directory, new SdkFixture.Invocation());
        world.afterWriteState = BuilderSessionTest.DIRT;
        ExtensionException nativeFailure = new ExtensionException("placement_failed", "Fixture write hook failed");
        world.writeFailure = nativeFailure;
        WorldSessionBackend backend = new WorldSessionBackend(world);
        BlockPosition position = new BlockPosition(-17, -64, 33);
        BuilderBackend.WriteOutcome outcome = backend.write(position, BuilderSessionTest.STONE, BuilderSessionTest.AIR);
        assertEquals(BuilderSessionTest.DIRT, outcome.actual());
        assertTrue(outcome.changed());
        assertEquals("placement_failed", ((BuilderException) outcome.failure()).code());
        assertSame(nativeFailure, outcome.failure().getCause());
        assertEquals(position, world.lastPosition);
        assertEquals(BuilderSessionTest.STONE, world.lastState);
        assertEquals(BuilderSessionTest.AIR, world.lastBefore);
        assertEquals(1, world.writes);
    }

    @Test void unavailableReadbackNeverBecomesFabricatedAirOrSuccessfulWrite() {
        SdkFixture.World world = new SdkFixture.World(directory, new SdkFixture.Invocation());
        world.missingReadback = true;
        world.writeFailure = new ExtensionException("chunk_unavailable", "Fixture readback became unavailable");
        BuilderBackend.WriteOutcome outcome = new WorldSessionBackend(world)
                .write(new BlockPosition(0, 64, 0), BuilderSessionTest.STONE);
        assertNull(outcome.actual());
        assertTrue(outcome.changed());
        assertEquals("chunk_unavailable", ((BuilderException) outcome.failure()).code());
        assertEquals(BuilderSessionTest.STONE, world.blocks.get(new BlockPosition(0, 64, 0)));
    }

    @Test void noOpAndCompareConflictKeepChangedFalseAndOriginalState() {
        SdkFixture.World world = new SdkFixture.World(directory, new SdkFixture.Invocation());
        BlockPosition position = new BlockPosition(0, 64, 0);
        world.blocks.put(position, BuilderSessionTest.DIRT);
        WorldSessionBackend backend = new WorldSessionBackend(world);
        BuilderBackend.WriteOutcome noOp = backend.write(position, BuilderSessionTest.DIRT, BuilderSessionTest.DIRT);
        assertFalse(noOp.changed());
        assertEquals(BuilderSessionTest.DIRT, noOp.actual());
        assertNull(noOp.failure());
        int writes = world.writes;
        BuilderBackend.WriteOutcome conflict = backend.write(position, BuilderSessionTest.STONE, BuilderSessionTest.AIR);
        assertFalse(conflict.changed());
        assertEquals(BuilderSessionTest.DIRT, conflict.actual());
        assertEquals("concurrent_edit", ((BuilderException) conflict.failure()).code());
        assertEquals(writes, world.writes);
        assertEquals(BuilderSessionTest.DIRT, world.blocks.get(position));
    }

    @Test void nonSdkWriteFailureIdentityIsRetainedRatherThanTranslatedToSuccess() {
        SdkFixture.World world = new SdkFixture.World(directory, new SdkFixture.Invocation());
        RuntimeException hookFailure = new IllegalStateException("Fixture hook failure");
        world.writeFailure = hookFailure;
        BuilderBackend.WriteOutcome outcome = new WorldSessionBackend(world)
                .write(new BlockPosition(0, 1, 0), BuilderSessionTest.STONE);
        assertSame(hookFailure, outcome.failure());
        assertTrue(outcome.changed());
        assertEquals(BuilderSessionTest.STONE, outcome.actual());
    }

    @Test void repairKeepsBothFullOpaqueImagesAndNullMeansNoChange() {
        SdkFixture.World world = new SdkFixture.World(directory, new SdkFixture.Invocation());
        WorldSessionBackend backend = new WorldSessionBackend(world);
        BlockPosition position = new BlockPosition(15, 64, -17);
        assertNull(backend.repair(position));
        world.repairBefore = "{\"id\":\"custom:connected_storage\",\"properties\":{\"east\":\"false\"},\"blockEntity\":\"{Items:[{id:'custom:gem'}]}\"}";
        world.repairIntended = "{\"id\":\"custom:connected_storage\",\"properties\":{\"east\":\"true\"},\"blockEntity\":\"{Items:[{id:'custom:gem'}]}\"}";
        BuilderBackend.RepairOutcome repair = backend.repair(position);
        assertEquals(world.repairBefore, repair.before());
        assertEquals(world.repairIntended, repair.intended());
        assertEquals(position, world.lastPosition);
        assertEquals(0, world.writes);
        assertEquals(0, world.notifications);
    }

    @Test void readsProofsTerrainAndTransformsUseExactCoordinatesAndDetachedStates() {
        SdkFixture.World world = new SdkFixture.World(directory, new SdkFixture.Invocation());
        WorldSessionBackend backend = new WorldSessionBackend(world);
        BlockPosition position = new BlockPosition(-17, 64, 33);
        String chest = "{\"id\":\"custom:storage\",\"properties\":{\"variant\":\"damp\"},\"blockEntity\":\"{Items:[]}\"}";
        world.blocks.put(position, chest);
        assertEquals(chest, backend.read(position));
        assertEquals(chest, backend.readNonAir(position));
        assertEquals(position, world.lastPosition);
        BlockSpec terrain = backend.terrainState(position);
        assertEquals("custom:storage", terrain.id());
        assertEquals("damp", terrain.properties().get("variant"));
        assertNull(terrain.blockEntity());
        BuilderBounds bounds = new BuilderBounds(-17, 64, 33, -17, 65, 34);
        assertFalse(backend.canonicalAir(bounds));
        assertEquals(bounds, world.lastBounds);
        assertTrue(backend.canonicalAir(new BuilderBounds(0, 64, 0, 15, 79, 15)));
        String cave = "{\"id\":\"minecraft:cave_air\",\"properties\":{}}";
        world.blocks.put(new BlockPosition(0, 64, 0), cave);
        assertEquals(cave, backend.readNonAir(new BlockPosition(0, 64, 0)));
        assertEquals(chest, backend.transform(chest, 270, "front_back"));
        assertEquals(chest, world.lastTransformState);
        assertEquals(270, world.lastRotation);
        assertEquals("front_back", world.lastMirror);
        assertEquals(319, backend.terrainTop(-17, 33, -64, 320));
        assertEquals(0, world.writes);
    }

    @Test void ownerDispatchTranslatesSdkFailureAndCloseReachesNativeSessionExactlyOnce() {
        SdkFixture.World world = new SdkFixture.World(directory, new SdkFixture.Invocation());
        WorldSessionBackend backend = new WorldSessionBackend(world);
        world.deadline = 123L;
        assertEquals(123L, backend.sliceDeadline());
        assertFalse(backend.isOwnerThread());
        String result = backend.call(() -> {
            assertTrue(backend.isOwnerThread());
            return "detached-result";
        });
        assertEquals("detached-result", result);
        assertEquals(1L, backend.dispatches());
        world.callFailure = new ExtensionException("stale_session", "Fixture exact connection replaced");
        BuilderException failure = assertThrows(BuilderException.class,
                () -> backend.call(() -> { throw new AssertionError("Stale action ran"); }));
        assertEquals("stale_session", failure.code());
        assertEquals(2L, backend.dispatches());
        backend.close();
        backend.close();
        assertEquals(1, world.closes);
        assertThrows(BuilderException.class, () -> backend.call(() -> "must-not-run"));
    }
}
