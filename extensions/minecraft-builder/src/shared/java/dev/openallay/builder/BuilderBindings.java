package dev.openallay.builder;

import static dev.openallay.api.extension.JavascriptHostValueType.INTEGER;
import static dev.openallay.api.extension.JavascriptHostValueType.STRING;

import com.google.gson.JsonElement;
import com.google.gson.JsonPrimitive;
import dev.openallay.api.extension.ExtensionCapability;
import dev.openallay.api.extension.JavascriptHostBinding;
import dev.openallay.api.extension.JavascriptHostMethod;
import dev.openallay.api.extension.JavascriptHostValueType;
import dev.openallay.api.extension.ExtensionInvocation;
import dev.openallay.api.extension.ExtensionException;
import java.util.List;
import java.util.Arrays;
import java.util.Collections;
import com.google.gson.JsonParser;
import java.util.Set;

/** Detached public methods; native world-write authority is never inherited by the Agent. */
final class BuilderBindings {
    static final String MODULE = "openallay_builder:native";
    static final String WORLD_WRITE = "openallay_builder:world_write";
    private BuilderBindings() {}

    static ExtensionCapability capability() {
        return new ExtensionCapability(WORLD_WRITE, "Builder world writes",
                "Allow Minecraft Builder to change the active integrated-server world, undo blocks, "
                        + "repair connection shapes and notify native physics. Does not grant Agent JVM access or remote-server writes.");
    }

    static JavascriptHostBinding binding() {
        return new JavascriptHostBinding(MODULE, Arrays.asList(
                method("open", false, (context, args) -> text(BuilderRuntime.open(context, string(args, 0))), STRING),
                method("context", false, (context, args) -> text(session(context, args).context()), STRING),
                method("read", false, (context, args) -> text(session(context, args).read(integer(args, 1), integer(args, 2), integer(args, 3))), STRING, INTEGER, INTEGER, INTEGER),
                method("readPositions", false, (context, args) -> text(session(context, args).readPositions(string(args, 1))), STRING, STRING),
                method("readRegion", false, (context, args) -> text(session(context, args).readRegion(string(args, 1))), STRING, STRING),
                method("scanColumns", false, (context, args) -> text(session(context, args).scanColumns(string(args, 1))), STRING, STRING),
                method("probeColumns", false, (context, args) -> text(session(context, args).probeColumns(string(args, 1))), STRING, STRING),
                method("write", true, (context, args) -> text(session(context, args).write(integer(args, 1), integer(args, 2), integer(args, 3), string(args, 4))), STRING, INTEGER, INTEGER, INTEGER, STRING),
                method("writeRegion", true, (context, args) -> text(session(context, args).writeRegion(string(args, 1))), STRING, STRING),
                method("transformState", false, (context, args) -> text(session(context, args).transformState(string(args, 1), integer(args, 2), string(args, 3))), STRING, STRING, INTEGER, STRING),
                method("updateConnections", true, (context, args) -> text(session(context, args).updateConnections(string(args, 1))), STRING, STRING),
                method("syncPhysics", true, (context, args) -> text(session(context, args).syncPhysics(string(args, 1))), STRING, STRING),
                voidMethod("saveTemplate", (context, args) -> session(context, args).saveTemplate(string(args, 1), string(args, 2)), STRING, STRING, STRING),
                method("loadTemplate", false, (context, args) -> text(session(context, args).loadTemplate(string(args, 1))), STRING, STRING),
                method("listTemplates", false, (context, args) -> text(session(context, args).listTemplates()), STRING),
                method("listOperations", false, (context, args) -> text(session(context, args).listOperations()), STRING),
                method("status", false, (context, args) -> text(session(context, args).status()), STRING),
                method("finish", false, (context, args) -> text(session(context, args).finish()), STRING),
                voidMethod("cancel", (context, args) -> session(context, args).cancel(), STRING),
                voidMethod("close", (context, args) -> session(context, args).close(), STRING),
                method("undo", true, (context, args) -> {
                    BuilderSession session = session(context, args);
                    String operationId = string(args, 1);
                    return text(operationId.isEmpty() ? session.undo() : session.undo(operationId));
                }, STRING, STRING)));
    }

    private static JavascriptHostMethod method(String name, boolean write,
            JavascriptHostMethod.Invoker invoker, JavascriptHostValueType... parameters) {
        Set<String> capabilities = write ? Collections.singleton(WORLD_WRITE) : Collections.<String>emptySet();
        return new JavascriptHostMethod(name, Arrays.asList(parameters), JavascriptHostValueType.STRING,
                capabilities, (context, args) -> {
                    context.requireActive();
                    // The public bridge also gates this method; keep the native handler's own gate.
                    if (write) context.requireCapability(WORLD_WRITE);
                    try { return invoker.invoke(context, args); }
                    catch (BuilderException failure) { throw publicFailure(failure); }
                    catch (ExtensionException failure) { throw publicFailure(new BuilderException(failure.code(), "Native host operation failed")); }
                    catch (RuntimeException failure) { throw new ExtensionException("builder_failure", "Builder operation failed; inspect the session status"); }
                });
    }

    private static JavascriptHostMethod voidMethod(String name, VoidInvoker invoker, JavascriptHostValueType... parameters) {
        return new JavascriptHostMethod(name, Arrays.asList(parameters), JavascriptHostValueType.NULL, Collections.<String>emptySet(),
                (context, args) -> {
                    context.requireActive();
                    try { invoker.invoke(context, args); return "null"; }
                    catch (BuilderException failure) { throw publicFailure(failure); }
                    catch (ExtensionException failure) { throw publicFailure(new BuilderException(failure.code(), "Native host operation failed")); }
                    catch (RuntimeException failure) { throw new ExtensionException("builder_failure", "Builder operation failed; inspect the session status"); }
                });
    }

    private static ExtensionException publicFailure(BuilderException failure) {
        String message;
        switch (failure.code()) {
            case "unsupported_topology":
                message = "Builder requires the active integrated server; remote-server writes are unavailable"; break;
            case "player_required":
                message = "Builder requires the exact local player invocation"; break;
            case "chunk_unavailable":
                message = "The requested chunk is not loaded; Builder does not generate chunks"; break;
            case "stale_session":
                message = "The exact player, connection, server or dimension binding changed"; break;
            case "session_required":
            case "invocation_required":
                message = "Builder session is not owned by this active invocation"; break;
            case "session_closed":
                message = "Builder session is closed or cancelled"; break;
            case "concurrent_edit":
                message = "A block changed after capture; Builder did not overwrite the conflicting image"; break;
            case "wrong_world":
            case "dimension_mismatch":
                message = "The requested world or dimension does not match the active Builder session"; break;
            case "invalid_argument":
            case "invalid_bounds":
            case "invalid_coordinate":
                message = "Builder requires valid bounds and exact 32-bit coordinates"; break;
            case "invalid_block_entity":
            case "missing_block_entity":
                message = "The native block-entity payload is invalid or unavailable"; break;
            case "unsupported_opaque_block_entity_transform":
                message = "The opaque block-entity payload cannot be transformed safely"; break;
            case "material_unavailable":
                message = "This world does not provide the material role required by this preset; choose available materials"; break;
            case "world_access_unavailable":
                message = "This host does not provide Minecraft world access"; break;
            case "placement_failed":
                message = "Native placement failed; inspect Builder status for verified partial progress"; break;
            case "unobserved_block":
                message = "The requested block has not been observed"; break;
            case "operation_required":
            case "operation_terminal":
                message = "The requested Builder operation is unavailable or terminal"; break;
            case "artifact_io":
                message = "Builder template or journal persistence failed"; break;
            case "wrong_owner":
            case "wrong_worker":
            case "owner_thread_wait":
            case "nested_invocation":
                message = "Builder rejected an invalid native execution owner"; break;
            default:
                message = "Builder native operation failed; inspect the session status"; break;
        }
        // No foreign message, stack or cause crosses the JavaScript host boundary.
        return new ExtensionException(failure.code(), message);
    }

    private static BuilderSession session(ExtensionInvocation context, List<String> args) {
        return BuilderRuntime.session(context, string(args, 0));
    }
    private static JsonElement argument(List<String> args, int index) {
        try { return JsonParser.parseString(args.get(index)); }
        catch (RuntimeException invalid) { throw new BuilderException("invalid_argument", "Builder requires exact JSON arguments", invalid); }
    }
    private static String string(List<String> args, int index) {
        JsonElement value = argument(args, index);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new BuilderException("invalid_argument", "Builder requires a JSON string argument");
        return value.getAsString();
    }
    private static int integer(List<String> args, int index) {
        JsonElement value = argument(args, index);
        if (!value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new BuilderException("invalid_argument", "Builder coordinates and rotations must be JSON numbers");
        try { return value.getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException | NumberFormatException invalid) {
            throw new BuilderException("invalid_argument", "Builder coordinates and rotations must be 32-bit integers", invalid);
        }
    }
    private static String text(String value) { return new JsonPrimitive(value).toString(); }
    @FunctionalInterface private interface VoidInvoker {
        void invoke(ExtensionInvocation context, List<String> args);
    }
}
