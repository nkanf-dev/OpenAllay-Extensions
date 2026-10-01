package dev.openallay.builder;

import static dev.openallay.extension.JavascriptHostValueType.INTEGER;
import static dev.openallay.extension.JavascriptHostValueType.STRING;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonPrimitive;
import dev.openallay.extension.ExtensionCapability;
import dev.openallay.extension.JavascriptHostBinding;
import dev.openallay.extension.JavascriptHostMethod;
import dev.openallay.extension.JavascriptHostValueType;
import dev.openallay.extension.JavascriptInvocationContext;
import dev.openallay.script.JavascriptExecutionException;
import java.util.List;
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
        return new JavascriptHostBinding(MODULE, List.of(
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
        Set<String> capabilities = write ? Set.of(WORLD_WRITE) : Set.of();
        return new JavascriptHostMethod(name, List.of(parameters), JavascriptHostValueType.STRING,
                capabilities, (context, args) -> {
                    context.requireActive();
                    // The public bridge also gates this method; keep the native handler's own gate.
                    if (write) context.requireCapability(WORLD_WRITE);
                    try { return invoker.invoke(context, args); }
                    catch (BuilderException failure) { throw publicFailure(failure); }
                });
    }

    private static JavascriptHostMethod voidMethod(String name, VoidInvoker invoker, JavascriptHostValueType... parameters) {
        return new JavascriptHostMethod(name, List.of(parameters), JavascriptHostValueType.NULL, Set.of(),
                (context, args) -> {
                    context.requireActive();
                    try { invoker.invoke(context, args); return JsonNull.INSTANCE; }
                    catch (BuilderException failure) { throw publicFailure(failure); }
                });
    }

    private static JavascriptExecutionException publicFailure(BuilderException failure) {
        String message = switch (failure.code()) {
            case "unsupported_topology" -> "Builder requires the active integrated server; remote-server writes are unavailable";
            case "player_required" -> "Builder requires the exact local player invocation";
            case "chunk_unavailable" -> "The requested chunk is not loaded; Builder does not generate chunks";
            case "stale_session" -> "The exact player, connection, server or dimension binding changed";
            case "session_required", "invocation_required" -> "Builder session is not owned by this active invocation";
            case "session_closed" -> "Builder session is closed or cancelled";
            case "concurrent_edit" -> "A block changed after capture; Builder did not overwrite the conflicting image";
            case "wrong_world", "dimension_mismatch" -> "The requested world or dimension does not match the active Builder session";
            case "invalid_argument", "invalid_bounds", "invalid_coordinate" -> "Builder requires valid bounds and exact 32-bit coordinates";
            case "invalid_block_entity", "missing_block_entity" -> "The native block-entity payload is invalid or unavailable";
            case "unsupported_opaque_block_entity_transform" -> "The opaque block-entity payload cannot be transformed safely";
            case "placement_failed" -> "Native placement failed; inspect Builder status for verified partial progress";
            case "unobserved_block" -> "The requested block has not been observed";
            case "operation_required", "operation_terminal" -> "The requested Builder operation is unavailable or terminal";
            case "artifact_io" -> "Builder template or journal persistence failed";
            case "wrong_owner", "wrong_worker", "owner_thread_wait", "nested_invocation" -> "Builder rejected an invalid native execution owner";
            default -> "Builder native operation failed; inspect the session status";
        };
        // No foreign message, stack or cause crosses the JavaScript host boundary.
        return new JavascriptExecutionException(failure.code(), message);
    }

    private static BuilderSession session(JavascriptInvocationContext context, List<JsonElement> args) {
        return BuilderRuntime.session(context, string(args, 0));
    }
    private static String string(List<JsonElement> args, int index) { return args.get(index).getAsString(); }
    private static int integer(List<JsonElement> args, int index) {
        try { return args.get(index).getAsBigDecimal().intValueExact(); }
        catch (ArithmeticException invalid) {
            throw new BuilderException("invalid_argument", "Builder coordinates and rotations must be 32-bit integers", invalid);
        }
    }
    private static JsonPrimitive text(String value) { return new JsonPrimitive(value); }
    @FunctionalInterface private interface VoidInvoker {
        void invoke(JavascriptInvocationContext context, List<JsonElement> args);
    }
}
