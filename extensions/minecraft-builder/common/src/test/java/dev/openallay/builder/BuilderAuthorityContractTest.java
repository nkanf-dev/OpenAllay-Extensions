package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.extension.ExtensionCapabilityPolicy;
import dev.openallay.extension.JavascriptInvocationContext;
import dev.openallay.extension.JavascriptInvocationParticipant;
import dev.openallay.extension.JavascriptInvocationScope;
import dev.openallay.extension.OpenAllayExtension;
import dev.openallay.extension.OpenAllayExtensionContribution;
import dev.openallay.extension.OpenAllayExtensionDescriptor;
import dev.openallay.extension.OpenAllayExtensionEnvironment;
import dev.openallay.extension.OpenAllayExtensionRegistry;
import dev.openallay.extension.OpenAllayExtensionState;
import dev.openallay.model.CancellationSignal;
import dev.openallay.script.JavascriptExecutionException;
import dev.openallay.script.JavascriptModuleCatalog;
import dev.openallay.script.JavascriptRuntimeLimits;
import dev.openallay.script.RhinoJavascriptRuntime;
import dev.openallay.script.extension.JavascriptDataModuleRegistry;
import dev.openallay.skill.SkillParser;
import dev.openallay.skill.SkillRepository;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Real restricted Rhino + public Extension SPI; no game, provider or JavaScript Java root. */
class BuilderAuthorityContractTest {
    @TempDir Path directory;

    @Test void normalRestrictedJavascriptOpensReadsAndExposesNoJavaBackend() {
        Fixture fixture = new Fixture(directory);
        JsonObject result = fixture.execute("read-only", false, true, """
                const native = require("openallay_builder:native");
                const building = require("openallay_builder:building");
                const b = building.open();
                return {java:typeof Java, packages:typeof Packages,
                    classAccess:typeof native.getClass, constructor:typeof native.constructor,
                    block:b.get_block(0,1,0), state:b.status().state, operations:b.list_operations()};
                """).getAsJsonObject();
        assertEquals("undefined", result.get("java").getAsString());
        assertEquals("undefined", result.get("packages").getAsString());
        assertEquals("undefined", result.get("classAccess").getAsString());
        assertEquals("undefined", result.get("constructor").getAsString());
        assertEquals("minecraft:air", result.get("block").getAsString());
        assertEquals("ready", result.get("state").getAsString());
        assertTrue(result.getAsJsonArray("operations").isEmpty());
        assertEquals(0, fixture.worldIdentityCreates);
        assertEquals(1, fixture.worldIdentityReads);
        assertEquals(0, fixture.backend.writeCount);
        assertFalse(fixture.authority.get().invocation().unrestrictedJavascript());
    }

    @Test void installationAndReadonlyAccessDoNotGrantAnyWorldChangingMethod() throws Exception {
        Fixture fixture = new Fixture(directory);
        try (var scope = fixture.open("no-writes", false, true)) {
            JsonPrimitive id = new JsonPrimitive(scope.invokeHostMethod(BuilderBindings.MODULE, "open",
                    List.of(new JsonPrimitive("{}"))).getAsString());
            Map<String, List<JsonElement>> requests = Map.of(
                    "write", List.of(id, new JsonPrimitive(0), new JsonPrimitive(1), new JsonPrimitive(0), new JsonPrimitive("{}")),
                    "writeRegion", List.of(id, new JsonPrimitive("[]")),
                    "updateConnections", List.of(id, new JsonPrimitive("{}")),
                    "syncPhysics", List.of(id, new JsonPrimitive("{}")),
                    "undo", List.of(id, new JsonPrimitive("")));
            for (var request : requests.entrySet()) {
                JavascriptExecutionException failure = assertThrows(JavascriptExecutionException.class,
                        () -> scope.invokeHostMethod(BuilderBindings.MODULE, request.getKey(), request.getValue()));
                assertEquals("javascript_extension_capability_denied", failure.code());
            }
            assertEquals(BuilderSessionTest.AIR, scope.invokeHostMethod(BuilderBindings.MODULE, "read",
                    List.of(id, new JsonPrimitive(0), new JsonPrimitive(1), new JsonPrimitive(0))).getAsString());
            scope.complete();
        }
        assertEquals(0, fixture.backend.writeCount);
        assertEquals(0, fixture.backend.notifyCount);
    }

    @Test void trustedBuilderHandlersKeepTheirOwnWriteGateEvenWithoutBridgeAdmission() throws Exception {
        Fixture fixture = new Fixture(directory);
        try (var scope = fixture.open("handler-gate", false, true)) {
            JsonPrimitive id = new JsonPrimitive(scope.invokeHostMethod(BuilderBindings.MODULE, "open",
                    List.of(new JsonPrimitive("{}"))).getAsString());
            Map<String, List<JsonElement>> requests = Map.of(
                    "write", List.of(id, new JsonPrimitive(0), new JsonPrimitive(1), new JsonPrimitive(0), new JsonPrimitive("{}")),
                    "writeRegion", List.of(id, new JsonPrimitive("[]")),
                    "updateConnections", List.of(id, new JsonPrimitive("{}")),
                    "syncPhysics", List.of(id, new JsonPrimitive("{}")),
                    "undo", List.of(id, new JsonPrimitive("")));
            for (var method : BuilderBindings.binding().methods()) {
                if (!requests.containsKey(method.name())) continue;
                JavascriptExecutionException failure = assertThrows(JavascriptExecutionException.class,
                        () -> method.invoker().invoke(fixture.authority.get(), requests.get(method.name())));
                assertEquals("javascript_extension_capability_denied", failure.code());
            }
            scope.complete();
        }
        assertEquals(0, fixture.backend.writeCount);
        assertEquals(0, fixture.worldIdentityCreates);
    }

    @Test void explicitBuilderGrantAllowsWritesButStillDoesNotProvideAgentJvm() {
        Fixture fixture = new Fixture(directory);
        JsonObject result = fixture.execute("write", true, true, """
                const b = require("openallay_builder:building").open();
                const result = b.place_block(0,1,0,"stone");
                return {java:typeof Java, result, block:b.get_block(0,1,0), status:b.finish()};
                """).getAsJsonObject();
        assertEquals("undefined", result.get("java").getAsString());
        assertEquals("minecraft:stone", result.get("block").getAsString());
        assertEquals(1, result.getAsJsonObject("status").get("writes").getAsInt());
        assertEquals(1, fixture.backend.writeCount);
        assertEquals(1, fixture.worldIdentityCreates);
        JsonObject readLater=fixture.execute("read-journal", false, true, """
                const b=require("openallay_builder:building").open();return {operations:b.list_operations()};
                """).getAsJsonObject();
        assertEquals(1, readLater.getAsJsonArray("operations").size());
        assertEquals(1, fixture.worldIdentityCreates, "Readonly listing must not initialize another world identity");
    }

    @Test void serverOriginHasNoWriteGrantEvenWhenLocalPolicyAllowsBuilder() {
        Fixture fixture = new Fixture(directory);
        JavascriptExecutionException failure = assertThrows(JavascriptExecutionException.class,
                () -> fixture.execute("server", true, false, """
                        const b = require("openallay_builder:building").open();
                        b.place_block(0,1,0,"stone");
                        return b.status();
                        """));
        assertEquals("javascript_extension_capability_denied", failure.code());
        assertEquals(0, fixture.backend.writeCount);
    }

    @Test void grantChangesDoNotRewriteAuthorityWithinAnExistingRequest() {
        Fixture fixture = new Fixture(directory);
        fixture.policy(true);
        fixture.registry.freezeJavascriptRequest("frozen", true);
        fixture.policy(false);
        JsonObject result = fixture.executeFrozen("frozen", """
                const b = require("openallay_builder:building").open();
                b.place_block(0,1,0,"stone");
                return b.finish();
                """).getAsJsonObject();
        assertEquals(1, result.get("writes").getAsInt());
        JavascriptExecutionException failure = assertThrows(JavascriptExecutionException.class,
                () -> fixture.execute("later", false, true, """
                        const b = require("openallay_builder:building").open();
                        b.place_block(1,1,0,"stone");return b.status();
                        """));
        assertEquals("javascript_extension_capability_denied", failure.code());
        assertEquals(1, fixture.backend.writeCount);
    }

    @Test void opaqueSessionsCannotCrossExecutionScopesOrAcceptClassAndCallbackArguments() {
        Fixture fixture = new Fixture(directory);
        String id = fixture.execute("first", true, true,
                "return require('openallay_builder:native').open('{}');").getAsString();
        JavascriptExecutionException stale = assertThrows(JavascriptExecutionException.class,
                () -> fixture.execute("second", true, true,
                        "return require('openallay_builder:native').context(%s);".formatted(new JsonPrimitive(id))));
        assertEquals("session_required", stale.code());
        JavascriptExecutionException callback = assertThrows(JavascriptExecutionException.class,
                () -> fixture.execute("callback", true, true,
                        "return require('openallay_builder:native').open(function(){});"));
        assertEquals("javascript_result_invalid", callback.code(), "Closed JSON transport rejects executable values before scalar type checking");
        JavascriptExecutionException fraction = assertThrows(JavascriptExecutionException.class,
                () -> fixture.execute("fraction", true, true, """
                        const native = require("openallay_builder:native");
                        return native.read(native.open('{}'),0.5,1,0);
                        """));
        assertEquals("javascript_extension_host_invalid", fraction.code());
        assertEquals(0, fixture.backend.writeCount);
    }

    @Test void nativeDomainErrorsKeepStableCodesWithoutForeignExceptionTextOrCauses() {
        Fixture fixture = new Fixture(directory) {
            @Override BuilderSessionTest.Backend backend(Path root) {
                return new BuilderSessionTest.Backend(root) {
                    @Override public String read(net.minecraft.core.BlockPos position) {
                        throw new BuilderException("chunk_unavailable", "synthetic-native-diagnostic-not-for-script",
                                new IllegalStateException("synthetic-internal-cause"));
                    }
                };
            }
        };
        JavascriptExecutionException failure = assertThrows(JavascriptExecutionException.class,
                () -> fixture.execute("domain-error", false, true, """
                        const b=require("openallay_builder:building").open();return b.get_block(0,1,0);
                        """));
        assertEquals("chunk_unavailable", failure.code());
        assertFalse(failure.getMessage().contains("synthetic-"));
        assertNull(failure.getCause());
        assertEquals(0, fixture.backend.writeCount);
    }

    @Test void returnedThrownAndCancelledScopesRevokeRetainedHostMethods() throws Exception {
        Fixture fixture = new Fixture(directory);
        JavascriptInvocationScope returned = fixture.open("returned", true, true);
        String id = returned.invokeHostMethod(BuilderBindings.MODULE, "open", List.of(new JsonPrimitive("{}"))).getAsString();
        returned.complete(); returned.close();
        assertThrows(JavascriptExecutionException.class, () -> returned.invokeHostMethod(
                BuilderBindings.MODULE, "read", List.of(new JsonPrimitive(id), new JsonPrimitive(0), new JsonPrimitive(1), new JsonPrimitive(0))));
        assertThrows(JavascriptExecutionException.class, () -> fixture.execute("thrown", true, true,
                "const b=require('openallay_builder:building').open();throw new Error('fixture');"));
        assertThrows(JavascriptExecutionException.class, fixture.authority.get()::requireActive);
        JavascriptInvocationScope cancelled = fixture.open("cancelled", true, true);
        cancelled.cancellation().cancel();
        assertThrows(RuntimeException.class, () -> cancelled.invokeHostMethod(BuilderBindings.MODULE, "open", List.of(new JsonPrimitive("{}"))));
        cancelled.close();
        assertEquals(0, fixture.registry.activeJavascriptInvocations());
    }

    @Test void installedButUnusedBuilderDoesNotCaptureAnUnrelatedNativeBackend() {
        Fixture fixture = new Fixture(directory);
        assertEquals(42, fixture.execute("unused", false, true, "return 42;").getAsInt());
        assertEquals(0, fixture.captures);
    }

    @Test void nativeWaitLongerThanTwoSecondsDoesNotSpendThePureJavascriptBudget() {
        Fixture fixture = new Fixture(directory) {
            @Override BuilderSessionTest.Backend backend(Path root) {
                return new BuilderSessionTest.Backend(root) {
                    @Override public String context() {
                        try { assertFalse(new CountDownLatch(1).await(2200, TimeUnit.MILLISECONDS)); }
                        catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new AssertionError(interrupted); }
                        return super.context();
                    }
                };
            }
        };
        JsonObject result = fixture.execute("slow-native", false, true, """
                const b=require("openallay_builder:building").open();
                return {java:typeof Java, maxY:b.context().maxY};
                """).getAsJsonObject();
        assertEquals("undefined", result.get("java").getAsString());
        assertEquals(320, result.get("maxY").getAsInt());
    }

    @Test void nativeCallsDoNotDisableTheSubsequentInfiniteJavascriptGuard() {
        Fixture fixture = new Fixture(directory);
        fixture.timeout = Duration.ofMillis(250);
        JavascriptExecutionException failure = assertThrows(JavascriptExecutionException.class,
                () -> fixture.execute("loop", false, true, """
                        const b=require("openallay_builder:building").open();b.context();
                        while(true){};
                        """));
        assertEquals("javascript_timeout", failure.code());
        assertEquals(1, fixture.captures, "The script entered the controlled native backend before the infinite loop");
    }

    @Test void safeHundredByHundredPlanKeepsCompleteTransportAndCooperativeNativeBatches() {
        Fixture fixture = new Fixture(directory);
        JsonObject result = fixture.execute("large-plan", true, true, """
                const b=require("openallay_builder:building").open();
                const result=b.build_floor(0,64,0,99,99,"stone");
                return {java:typeof Java, result, status:b.finish()};
                """).getAsJsonObject();
        assertEquals("undefined", result.get("java").getAsString());
        assertEquals(10_000, result.getAsJsonObject("result").get("writes").getAsInt());
        assertEquals(10_000, result.getAsJsonObject("status").get("writes").getAsInt());
        assertEquals(10_000, fixture.backend.writeCount);
        assertEquals(12, fixture.backend.callCount, "Two preparation slices and ten apply slices, not per-voxel dispatch");
    }

    static class Fixture {
        final JavascriptModuleCatalog modules = new JavascriptModuleCatalog(Map.of());
        final OpenAllayExtensionRegistry registry = new OpenAllayExtensionRegistry(
                new OpenAllayExtensionEnvironment("fabric", "26.2", "0.2.2"),
                new JavascriptDataModuleRegistry(), modules,
                new SkillRepository(new SkillParser(), List.of("openallay:run_javascript")), Set.of());
        final AtomicReference<JavascriptInvocationContext> authority = new AtomicReference<>();
        final BuilderSessionTest.Backend backend;
        int captures, worldIdentityCreates, worldIdentityReads;
        boolean identityExists;
        Duration timeout = RhinoJavascriptRuntime.DEFAULT_TIMEOUT;

        Fixture(Path directory) {
            backend = backend(directory);
            BuilderExtension extension = new BuilderExtension("fabric");
            var declared = extension.contribution();
            JavascriptInvocationParticipant participant = new JavascriptInvocationParticipant() {
                @Override public String id() { return "openallay_builder:test_invocation"; }
                @Override public AutoCloseable open(JavascriptInvocationContext context) {
                    authority.set(context);
                    return BuilderRuntime.install(context, "test", (bridge, invocation) -> { captures++; return backend; });
                }
            };
            var contribution = new OpenAllayExtensionContribution(declared.dataModules(), declared.javascriptModules(),
                    declared.skills(), declared.resultViews(), List.of(participant), declared.hostBindings(), declared.capabilities());
            var registration = registry.register(new OpenAllayExtension() {
                @Override public OpenAllayExtensionDescriptor descriptor() { return extension.descriptor(); }
                @Override public OpenAllayExtensionContribution contribution() { return contribution; }
            });
            assertEquals(OpenAllayExtensionState.ACTIVE, registration.state(), registration.diagnostic());
        }
        BuilderSessionTest.Backend backend(Path directory) {
            return new BuilderSessionTest.Backend(directory) {
                @Override public String worldId() {
                    authority.get().requireCapability(BuilderBindings.WORLD_WRITE);
                    if (!identityExists) { worldIdentityCreates++; identityExists=true; }
                    return "test-world";
                }
                @Override public java.util.Optional<String> existingWorldId() {
                    worldIdentityReads++;
                    return identityExists ? java.util.Optional.of("test-world") : java.util.Optional.empty();
                }
            };
        }
        void policy(boolean writes) {
            registry.replaceCapabilityPolicy(new ExtensionCapabilityPolicy(writes
                    ? Map.of("openallay:builder", Set.of(BuilderBindings.WORLD_WRITE)) : Map.of()));
        }
        JavascriptInvocationScope open(String requestId, boolean writes, boolean clientLocal) {
            policy(writes); registry.freezeJavascriptRequest(requestId, clientLocal);
            return openFrozen(requestId);
        }
        JavascriptInvocationScope openFrozen(String requestId) {
            var scope = registry.prepareJavascriptInvocation(ToolInvocationContext.developmentConsole(requestId), new CancellationSignal());
            scope.open(ignored -> {});
            return scope;
        }
        JsonElement execute(String requestId, boolean writes, boolean clientLocal, String source) {
            policy(writes); registry.freezeJavascriptRequest(requestId, clientLocal);
            return executeFrozen(requestId, source);
        }
        JsonElement executeFrozen(String requestId, String source) {
            try (var scope = openFrozen(requestId)) {
                var runtime = new RhinoJavascriptRuntime(timeout, JavascriptRuntimeLimits.DEFAULT, modules);
                JsonElement value = runtime.execute(source, Map.of(), Map.of(), Map.of(), Map.of(), ignored -> {},
                        scope.cancellation(), null, null, false, scope).value();
                scope.complete();
                return value;
            }
        }
    }
}
