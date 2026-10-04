package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.openallay.api.extension.ExtensionException;
import dev.openallay.api.extension.JavascriptHostMethod;
import dev.openallay.builder.storage.OperationJournal;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** SDK host boundary tests. These fixtures never import or construct Minecraft types. */
class BuilderRuntimeTest {
    @TempDir Path directory;

    private static JavascriptHostMethod method(String name) {
        for (JavascriptHostMethod value : BuilderBindings.binding().methods())
            if (value.name().equals(name)) return value;
        throw new AssertionError("Missing host fixture method: " + name);
    }
    private static String jsonString(String value) { return new JsonPrimitive(value).toString(); }

    @Test void installedButUnusedBuilderNeverRequestsOrOpensWorldAccess() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.Host host = new SdkFixture.Host(null);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            assertEquals(0, host.accessRequests);
            assertEquals(0, host.opens);
            assertEquals(1, invocation.cancellationListeners.size());
        }
        assertEquals(0, host.accessRequests);
        assertEquals(0, host.opens);
        assertTrue(invocation.evidence.isEmpty());
    }

    @Test void firstOpenCapturesExactInvocationOnceAndReusesScopedWorldSession() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        SdkFixture.Host host = new SdkFixture.Host(world);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            String first = BuilderRuntime.open(invocation, "{}");
            String second = BuilderRuntime.open(invocation, "{\"label\":\"second\"}");
            assertNotEquals(first, second);
            assertEquals(1, host.accessRequests);
            assertEquals(1, host.opens);
            assertSame(invocation, host.openedInvocation);
            assertEquals(0, world.calls);
            assertEquals(BuilderSessionTest.AIR, BuilderRuntime.session(invocation, first).read(-17, -64, 33));
            assertEquals(1, world.calls);
            assertEquals(1, world.reads);
            assertEquals(0, world.worldIdentityCreates);
            assertEquals("1.20.1", invocation.evidence.get(0).gameVersion());
            assertEquals("fabric", invocation.evidence.get(0).loader());
            invocation.successful = true;
        }
        assertEquals(1, world.closes);
        assertTrue(new OperationJournal(directory.resolve("journals")).list().isEmpty());
    }

    @Test void missingWorldAccessFailsOnlyOnOpenAndNeverPublishesArtifacts() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.Host host = new SdkFixture.Host(null);
        host.accessMissing = true;
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            assertEquals(0, host.accessRequests);
            ExtensionException first = assertThrows(ExtensionException.class,
                    () -> BuilderRuntime.open(invocation, "{}"));
            ExtensionException second = assertThrows(ExtensionException.class,
                    () -> BuilderRuntime.open(invocation, "{}"));
            assertEquals("world_access_unavailable", first.code());
            assertSame(first, second, "A failed capture must not probe or rebind a different world");
            assertEquals(1, host.accessRequests);
            assertEquals(0, host.opens);
            try (java.util.stream.Stream<Path> files = Files.list(directory)) { assertEquals(0L, files.count()); }
        }
        assertTrue(invocation.evidence.isEmpty());
    }

    @Test void nullWorldSessionFromSdkHostFailsActionablyAndCannotRetryAnotherCapture() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.Host host = new SdkFixture.Host(null);
        host.returnsNullWorld = true;
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            ExtensionException failure = assertThrows(ExtensionException.class,
                    () -> BuilderRuntime.open(invocation, "{}"));
            assertEquals("world_access_unavailable", failure.code());
            assertSame(failure, assertThrows(ExtensionException.class,
                    () -> BuilderRuntime.open(invocation, "{}")));
            assertEquals(1, host.accessRequests);
            assertEquals(1, host.opens);
            assertTrue(invocation.evidence.isEmpty());
            assertFalse(Files.exists(directory.resolve("journals")));
            assertFalse(Files.exists(directory.resolve("templates")));
        }
    }

    @Test void wrongWorkerCloseOfUnusedFrameDoesNotRevokeItOrLeaveNestedFrameOnOwner() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        SdkFixture.Host host = new SdkFixture.Host(world);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            try (FixtureValues.TestExecutorService foreign = FixtureValues.executor()) {
                java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                        () -> foreign.submit(() -> { scope.close(); return null; }).get());
                assertEquals("wrong_worker", ((BuilderException) failure.getCause()).code());
            }
            assertEquals(0, host.accessRequests);
            assertEquals(0, world.closes);
            String id = BuilderRuntime.open(invocation, "{}");
            assertEquals(BuilderSessionTest.AIR, BuilderRuntime.session(invocation, id).read(0, 1, 0));
            assertEquals(1, host.opens);
            assertEquals(1, world.calls);
            invocation.successful = true;
        }
        assertEquals(1, world.closes);
        SdkFixture.Invocation next = new SdkFixture.Invocation();
        SdkFixture.Host nextHost = new SdkFixture.Host(null);
        try (AutoCloseable nextScope = BuilderRuntime.install(next, nextHost)) {
            assertEquals(0, nextHost.opens, "Owner frame is removed only by its successful close");
        }
    }

    @Test void repeatedCloseOfPreviousScopeCannotRemoveCurrentInvocationFrame() throws Exception {
        SdkFixture.Invocation first = new SdkFixture.Invocation();
        AutoCloseable previous = BuilderRuntime.install(first, new SdkFixture.Host(null));
        previous.close();
        SdkFixture.Invocation next = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, next);
        SdkFixture.Host host = new SdkFixture.Host(world);
        try (AutoCloseable current = BuilderRuntime.install(next, host)) {
            previous.close();
            String id = BuilderRuntime.open(next, "{}");
            assertEquals(BuilderSessionTest.AIR, BuilderRuntime.session(next, id).read(0, 1, 0));
            assertSame(next, host.openedInvocation);
            assertEquals(1, host.opens);
        }
        assertEquals(1, world.closes);
    }

    @Test void nativeOpenFailureIsCachedWithoutClaimingASessionOrSuccess() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        SdkFixture.Host host = new SdkFixture.Host(world);
        host.openFailure = new ExtensionException("unsupported_topology", "Fixture remote world cannot write");
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            assertSame(host.openFailure, assertThrows(ExtensionException.class,
                    () -> BuilderRuntime.open(invocation, "{}")));
            assertSame(host.openFailure, assertThrows(ExtensionException.class,
                    () -> BuilderRuntime.open(invocation, "{}")));
            assertEquals(1, host.opens);
            assertEquals(0, world.calls);
            assertEquals(0, world.closes);
            assertFalse(Files.exists(directory.resolve("journals")));
        }
        assertTrue(invocation.evidence.isEmpty());
    }

    @Test void cancellationBeforeFirstOpenDoesNotCaptureWorldAccess() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.Host host = new SdkFixture.Host(null);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            invocation.cancel();
            ExtensionException failure = assertThrows(ExtensionException.class,
                    () -> BuilderRuntime.open(invocation, "{}"));
            assertEquals("cancelled", failure.code());
            assertEquals(0, host.accessRequests);
            assertEquals(0, host.opens);
        }
    }

    @Test void cancelledRetainedSessionCannotDispatchAndScopeCloseIsIdempotent() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        SdkFixture.Host host = new SdkFixture.Host(world);
        AutoCloseable scope = BuilderRuntime.install(invocation, host);
        try {
            BuilderSession session = BuilderRuntime.session(invocation, BuilderRuntime.open(invocation, "{}"));
            invocation.cancel();
            assertThrows(RuntimeException.class, () -> session.read(0, 1, 0));
            assertEquals(0, world.calls);
            assertEquals(0, world.writes);
            assertTrue(invocation.evidence.isEmpty());
        } finally { scope.close(); }
        scope.close();
        assertEquals(1, world.closes);
        assertEquals(1, host.opens);
    }

    @Test void sessionsRejectOtherInvocationIdentitiesAndLaterExecutionScopes() throws Exception {
        SdkFixture.Invocation first = new SdkFixture.Invocation();
        SdkFixture.World firstWorld = new SdkFixture.World(directory.resolve("first"), first);
        String sessionId;
        try (AutoCloseable scope = BuilderRuntime.install(first, new SdkFixture.Host(firstWorld))) {
            sessionId = BuilderRuntime.open(first, "{}");
            BuilderException wrongInvocation = assertThrows(BuilderException.class,
                    () -> BuilderRuntime.session(new SdkFixture.Invocation(), sessionId));
            assertEquals("invocation_required", wrongInvocation.code());
        }
        SdkFixture.Invocation second = new SdkFixture.Invocation();
        SdkFixture.Host secondHost = new SdkFixture.Host(null);
        try (AutoCloseable scope = BuilderRuntime.install(second, secondHost)) {
            BuilderException stale = assertThrows(BuilderException.class,
                    () -> BuilderRuntime.session(second, sessionId));
            assertEquals("session_required", stale.code());
            assertEquals(0, secondHost.opens);
        }
    }

    @Test void nestedInstallIsRejectedWithoutReplacingExistingFrame() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        SdkFixture.Host host = new SdkFixture.Host(world);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            BuilderException failure = assertThrows(BuilderException.class,
                    () -> BuilderRuntime.install(new SdkFixture.Invocation(), new SdkFixture.Host(null)));
            assertEquals("nested_invocation", failure.code());
            assertNotNull(BuilderRuntime.open(invocation, "{}"));
            assertSame(invocation, host.openedInvocation);
        }
        assertEquals(1, world.closes);
    }

    @Test void hostHandlersParseExactJsonScalarsBeforeDispatchAndPreserveStatePayload() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        invocation.writesAllowed = true;
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            String id = JsonParser.parseString(method("open").invoker().invoke(invocation,
                    FixtureValues.list(jsonString("{}")))).getAsString();
            String idJson = jsonString(id);
            for (String invalid : FixtureValues.list("1.5", "\"1\"", "2147483648", "true", "null")) {
                ExtensionException failure = assertThrows(ExtensionException.class,
                        () -> method("read").invoker().invoke(invocation, FixtureValues.list(idJson, invalid, "1", "0")));
                assertEquals("invalid_argument", failure.code());
            }
            assertEquals(0, world.calls);
            String read = method("read").invoker().invoke(invocation, FixtureValues.list(idJson, "-17", "-64", "33"));
            assertEquals(BuilderSessionTest.AIR, JsonParser.parseString(read).getAsString());
            String exact = "{\"id\":\"custom:chest\",\"properties\":{\"facing\":\"west\",\"waterlogged\":\"true\"},"
                    + "\"blockEntity\":\"{CustomName:'箱',Items:[{Slot:0b,id:'custom:gem',count:2}]}\"}";
            String result = method("write").invoker().invoke(invocation,
                    FixtureValues.list(idJson, "-17", "64", "33", jsonString(exact)));
            assertEquals(1, JsonParser.parseString(JsonParser.parseString(result).getAsString())
                    .getAsJsonObject().get("verified").getAsInt());
            assertEquals(exact, world.lastState);
            assertEquals(BuilderSessionTest.AIR, world.lastBefore);
            assertEquals(1, world.writes);
            invocation.successful = true;
        }
        OperationJournal.Snapshot operation = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(OperationJournal.Status.COMPLETED, operation.status());
        assertEquals("true", operation.entries().get(0).verified().properties().get("waterlogged"));
        assertTrue(operation.entries().get(0).verified().blockEntity().contains("custom:gem"));
    }

    @Test void readonlyHostHandlersCannotEnterWritePreparationOrCreateWorldIdentity() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            String id = BuilderRuntime.open(invocation, "{}");
            ExtensionException failure = assertThrows(ExtensionException.class,
                    () -> method("write").invoker().invoke(invocation,
                            FixtureValues.list(jsonString(id), "0", "1", "0", jsonString(BuilderSessionTest.STONE))));
            assertEquals("capability_denied", failure.code());
            assertEquals(0, world.calls);
            assertEquals(0, world.previews);
            assertEquals(0, world.writes);
            assertEquals(0, world.worldIdentityCreates);
            assertEquals(BuilderSessionTest.AIR, BuilderRuntime.session(invocation, id).read(0, 1, 0));
            assertEquals(1, invocation.writeGateChecks);
        }
        assertTrue(new OperationJournal(directory.resolve("journals")).list().isEmpty());
    }

    @Test void sdkColumnScanFailureIsNormalizedInsideOwnerActionAndPublishesOnlyPartialEvidence() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation) {
            @Override public int terrainTop(int x, int z, int minY, int maxY) {
                active();
                if (x == 1) throw new ExtensionException("chunk_unavailable", "Fixture unloaded scan column");
                return 64;
            }
            @Override public String terrainState(int x, int y, int z) { active(); return BuilderSessionTest.STONE; }
        };
        world.blocks.put(new dev.openallay.builder.storage.BlockPosition(0, 64, 0), BuilderSessionTest.STONE);
        world.blocks.put(new dev.openallay.builder.storage.BlockPosition(2, 64, 0), BuilderSessionTest.STONE);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            BuilderSession session = BuilderRuntime.session(invocation, BuilderRuntime.open(invocation, "{}"));
            com.google.gson.JsonArray results = JsonParser.parseString(session.probeColumns(
                    TerrainProbeTest.command(0, 2, null, 2))).getAsJsonArray();
            assertEquals(3, results.size());
            for (int index : new int[] {0, 2}) {
                JsonObject success = results.get(index).getAsJsonObject();
                assertEquals(64, success.getAsJsonObject("column").get("y").getAsInt());
                assertEquals(3, success.getAsJsonArray("cells").size());
                assertEquals("minecraft:stone", success.getAsJsonArray("cells").get(0)
                        .getAsJsonObject().getAsJsonObject("state").get("id").getAsString());
                assertFalse(success.has("error"));
            }
            JsonObject failure = results.get(1).getAsJsonObject();
            assertTrue(failure.get("column").isJsonNull());
            assertEquals(0, failure.getAsJsonArray("cells").size(), "Failed scan is not an observed air column");
            assertEquals("chunk_unavailable", failure.getAsJsonObject("error").get("code").getAsString());
            assertEquals(1, invocation.evidence.size());
            assertEquals(dev.openallay.api.extension.ExtensionEvidence.Completeness.PARTIAL,
                    invocation.evidence.get(0).completeness());
            assertEquals("8", invocation.evidence.get(0).details().get("openallay_builder:count"));
            assertEquals(0, world.writes);
            assertEquals(0, world.worldIdentityCreates);
        }
    }

    @Test void sdkHeadroomFailureKeepsValidGroundButNeverPublishesIncompleteCellsAsComplete() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation) {
            @Override public int terrainTop(int x, int z, int minY, int maxY) { active(); return 64; }
            @Override public String terrainState(int x, int y, int z) { active(); return BuilderSessionTest.STONE; }
            @Override public String read(int x, int y, int z) {
                if (x == 1 && y == 65) throw new ExtensionException("chunk_unavailable", "Fixture unloaded headroom");
                return super.read(x, y, z);
            }
        };
        for (int x = 0; x <= 2; x++)
            world.blocks.put(new dev.openallay.builder.storage.BlockPosition(x, 64, 0), BuilderSessionTest.STONE);
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            BuilderSession session = BuilderRuntime.session(invocation, BuilderRuntime.open(invocation, "{}"));
            com.google.gson.JsonArray results = JsonParser.parseString(session.probeColumns(
                    TerrainProbeTest.command(0, 2, null, 2))).getAsJsonArray();
            assertEquals(3, results.size());
            assertEquals(3, results.get(0).getAsJsonObject().getAsJsonArray("cells").size());
            assertEquals(3, results.get(2).getAsJsonObject().getAsJsonArray("cells").size());
            JsonObject failure = results.get(1).getAsJsonObject();
            assertEquals(64, failure.getAsJsonObject("column").get("y").getAsInt());
            assertEquals(0, failure.getAsJsonArray("cells").size());
            assertEquals("chunk_unavailable", failure.getAsJsonObject("error").get("code").getAsString());
            assertEquals(dev.openallay.api.extension.ExtensionEvidence.Completeness.PARTIAL,
                    invocation.evidence.get(0).completeness());
            assertEquals("10", invocation.evidence.get(0).details().get("openallay_builder:count"));
            assertEquals(0, world.writes);
        }
    }

    @Test void sdkGlobalSessionRevocationIsNeverDowngradedToPartialColumnEvidence() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation) {
            @Override public int terrainTop(int x, int z, int minY, int maxY) {
                throw new ExtensionException("stale_session", "Fixture exact connection replaced");
            }
        };
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            BuilderSession session = BuilderRuntime.session(invocation, BuilderRuntime.open(invocation, "{}"));
            BuilderException failure = assertThrows(BuilderException.class,
                    () -> session.probeColumns(TerrainProbeTest.command(0, 2, null, 2)));
            assertEquals("stale_session", failure.code());
            assertTrue(invocation.evidence.isEmpty());
            assertEquals(0, world.reads);
            assertEquals(0, world.writes);
        }
    }

    @Test void sdkChangedWriteWithMissingReadbackKeepsPendingIntentAndPublicFailureCode() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        invocation.writesAllowed = true;
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        world.missingReadback = true;
        world.writeFailure = new ExtensionException("chunk_unavailable", "Fixture target readback unavailable");
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            String id = BuilderRuntime.open(invocation, "{}");
            ExtensionException failure = assertThrows(ExtensionException.class,
                    () -> method("write").invoker().invoke(invocation,
                            FixtureValues.list(jsonString(id), "0", "1", "0", jsonString(BuilderSessionTest.STONE))));
            assertEquals("chunk_unavailable", failure.code());
            assertNull(failure.getCause());
            JsonObject status = JsonParser.parseString(BuilderRuntime.session(invocation, id).status()).getAsJsonObject();
            assertEquals("failed-partial", status.get("state").getAsString());
            assertEquals(1, status.get("writes").getAsInt());
            assertEquals(1, world.writes);
            assertEquals(BuilderSessionTest.STONE, world.blocks.get(new dev.openallay.builder.storage.BlockPosition(0, 1, 0)));
            assertTrue(invocation.evidence.isEmpty(), "Unavailable readback cannot publish verified write evidence");
            invocation.successful = true;
        }
        OperationJournal.Snapshot operation = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(OperationJournal.Status.FAILED, operation.status());
        assertEquals(1, operation.entries().size());
        OperationJournal.Entry entry = operation.entries().get(0);
        assertTrue(entry.pending());
        assertNull(entry.verified());
        assertFalse(entry.undoEligible());
        assertEquals("minecraft:air", entry.before().id());
        assertEquals("minecraft:stone", entry.intended().id());
        assertEquals(entry.intended(), entry.expected(), "Pending intent is diagnostic, not fabricated verified air");
        assertEquals(1, world.closes);
    }

    @Test void sdkMissingReadbackWithoutNativeFailureStillFailsUnobservedAndNeverVerifiesIntent() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        invocation.writesAllowed = true;
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        world.missingReadback = true;
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            String id = BuilderRuntime.open(invocation, "{}");
            ExtensionException failure = assertThrows(ExtensionException.class,
                    () -> method("write").invoker().invoke(invocation,
                            FixtureValues.list(jsonString(id), "0", "1", "0", jsonString(BuilderSessionTest.STONE))));
            assertEquals("unobserved_block", failure.code());
            assertNull(failure.getCause());
            BuilderSession session = BuilderRuntime.session(invocation, id);
            JsonObject status = JsonParser.parseString(session.status()).getAsJsonObject();
            assertEquals("failed-partial", status.get("state").getAsString());
            assertEquals(1, status.get("writes").getAsInt());
            assertThrows(BuilderException.class, session::finish);
            assertEquals(1, world.writes);
            assertTrue(invocation.evidence.isEmpty());
            invocation.successful = true;
        }
        OperationJournal.Snapshot operation = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(OperationJournal.Status.FAILED, operation.status());
        assertTrue(operation.entries().get(0).pending());
        assertNull(operation.entries().get(0).verified());
        assertFalse(operation.entries().get(0).undoEligible());
        assertEquals("minecraft:stone", operation.entries().get(0).intended().id());
    }

    @Test void sdkUndoMissingReadbackCountsChangedWriteButLeavesUndoIntentPending() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        invocation.writesAllowed = true;
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        String originalId;
        String undoId;
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            String id = BuilderRuntime.open(invocation, "{}");
            BuilderSession session = BuilderRuntime.session(invocation, id);
            session.write(0, 1, 0, BuilderSessionTest.STONE);
            originalId = JsonParser.parseString(session.finish()).getAsJsonObject().get("operationId").getAsString();
            assertEquals(1, invocation.evidence.size());
            world.missingReadback = true;
            world.writeFailure = new ExtensionException("chunk_unavailable", "Fixture undo readback unavailable");
            ExtensionException failure = assertThrows(ExtensionException.class,
                    () -> method("undo").invoker().invoke(invocation,
                            FixtureValues.list(jsonString(id), jsonString(originalId))));
            assertEquals("chunk_unavailable", failure.code());
            assertNull(failure.getCause());
            JsonObject status = JsonParser.parseString(session.status()).getAsJsonObject();
            assertEquals("failed-partial", status.get("state").getAsString());
            assertEquals(2, status.get("writes").getAsInt(), "Changed undo write remains accounted without readback");
            undoId = status.get("operationId").getAsString();
            assertNotEquals(originalId, undoId);
            assertEquals(2, world.writes);
            assertEquals(BuilderSessionTest.AIR, world.blocks.get(new dev.openallay.builder.storage.BlockPosition(0, 1, 0)));
            assertEquals(1, invocation.evidence.size(), "Unobserved undo cannot claim a verified restored image");
            assertEquals("openallay_builder:write-readback", invocation.evidence.get(0).sourceId());
            invocation.successful = true;
        }
        OperationJournal journal = new OperationJournal(directory.resolve("journals"));
        OperationJournal.Snapshot original = journal.load(originalId);
        OperationJournal.Snapshot undo = journal.load(undoId);
        assertEquals(OperationJournal.Status.COMPLETED, original.status());
        assertEquals("minecraft:stone", original.entries().get(0).verified().id());
        assertEquals(OperationJournal.Status.FAILED, undo.status());
        assertEquals(1, undo.entries().size());
        OperationJournal.Entry entry = undo.entries().get(0);
        assertTrue(entry.pending());
        assertNull(entry.verified());
        assertFalse(entry.undoEligible());
        assertEquals("minecraft:stone", entry.before().id());
        assertEquals("minecraft:air", entry.intended().id());
        assertEquals(1, world.closes);
    }

    @Test void sdkChangedWriteFailureKeepsActualJournalAndStablePublicCode() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        invocation.writesAllowed = true;
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        world.afterWriteState = BuilderSessionTest.DIRT;
        world.writeFailure = new ExtensionException("placement_failed", "synthetic-private-hook", new IllegalStateException("private-cause"));
        try (AutoCloseable scope = BuilderRuntime.install(invocation, new SdkFixture.Host(world))) {
            String id = BuilderRuntime.open(invocation, "{}");
            ExtensionException failure = assertThrows(ExtensionException.class,
                    () -> method("write").invoker().invoke(invocation,
                            FixtureValues.list(jsonString(id), "0", "1", "0", jsonString(BuilderSessionTest.STONE))));
            assertEquals("placement_failed", failure.code());
            assertFalse(failure.getMessage().contains("synthetic-private"));
            assertNull(failure.getCause(), "Foreign native diagnostics do not cross the host boundary");
            JsonObject status = JsonParser.parseString(BuilderRuntime.session(invocation, id).status()).getAsJsonObject();
            assertEquals("failed-partial", status.get("state").getAsString());
            assertEquals(1, status.get("writes").getAsInt());
            assertEquals(1, world.writes);
            invocation.successful = true;
        }
        OperationJournal.Snapshot operation = new OperationJournal(directory.resolve("journals")).list().get(0);
        assertEquals(OperationJournal.Status.FAILED, operation.status());
        assertEquals("minecraft:stone", operation.entries().get(0).intended().id());
        assertEquals("minecraft:dirt", operation.entries().get(0).verified().id());
        assertEquals(1, world.closes);
        assertEquals("1", invocation.evidence.get(0).details().get("openallay_builder:count"));
    }
}
