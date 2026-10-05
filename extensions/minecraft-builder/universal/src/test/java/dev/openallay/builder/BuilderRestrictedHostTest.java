package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import dev.openallay.api.extension.ExtensionContribution;
import dev.openallay.api.extension.JavascriptHostBinding;
import dev.openallay.api.extension.JavascriptHostMethod;
import dev.openallay.api.extension.JavascriptModuleSource;
import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.OperationJournal;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mozilla.javascript.BaseFunction;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ImporterTopLevel;
import org.mozilla.javascript.NativeJSON;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/** Restricted Rhino source/SDK contract test, not a Minecraft adapter or the production core bridge. */
final class BuilderRestrictedHostTest {
    @TempDir Path directory;

    @Test void enabledBuilderReadsWritesFinishesAndUndoesThroughNativeModuleWithoutJvmAccess() throws Exception {
        SdkFixture.Invocation invocation = new SdkFixture.Invocation();
        SdkFixture.World world = new SdkFixture.World(directory, invocation);
        BlockPosition position = new BlockPosition(-17, 64, 33);
        world.blocks.put(position, BuilderSessionTest.DIRT);
        SdkFixture.Host host = new SdkFixture.Host(world);
        String exact = "{\"id\":\"custom:storage\",\"properties\":{\"facing\":\"west\"},"
                + "\"blockEntity\":\"{Items:[{Slot:0b,id:'custom:gem',count:2}]}\"}";
        try (AutoCloseable scope = BuilderRuntime.install(invocation, host)) {
            ExtensionContribution contribution = new BuilderExtension().contribution(host);
            JsonObject result = evaluate(contribution, invocation,
                    "var building=require('openallay_builder:building'),b=building.open({seed:17});"
                    + "var before=b.get_block_full(-17,64,33);"
                    + "var placed=b.place_block(-17,64,33," + exact + ");"
                    + "var after=b.get_block_full(-17,64,33),finished=b.finish();"
                    + "var undone=b.undo(finished.operationId),restored=b.get_block_full(-17,64,33);"
                    + "return {before:before,placed:placed,after:after,finished:finished,"
                    + "undone:undone,restored:restored,noJvm:"
                    + "['Java','Packages','java','javax','JavaAdapter','JavaImporter','importClass',"
                    + "'importPackage','getClass'].every(function(name){return typeof global[name]==='undefined';})};");
            assertTrue(result.get("noJvm").getAsBoolean());
            assertEquals(JsonParser.parseString(BuilderSessionTest.DIRT), result.get("before"));
            assertEquals(1, result.getAsJsonObject("placed").get("writes").getAsInt());
            assertEquals(JsonParser.parseString(exact), result.get("after"));
            assertEquals("completed", result.getAsJsonObject("finished").get("state").getAsString());
            assertEquals(1, result.getAsJsonObject("finished").get("writes").getAsInt());
            assertEquals(1, result.getAsJsonObject("undone").get("restored").getAsInt());
            assertEquals(0, result.getAsJsonObject("undone").getAsJsonArray("conflicts").size());
            assertEquals(0, result.getAsJsonObject("undone").getAsJsonArray("uncertain").size());
            assertEquals(JsonParser.parseString(BuilderSessionTest.DIRT), result.get("restored"));
            assertEquals(BuilderSessionTest.DIRT, world.blocks.get(position));
            assertEquals(1, host.opens);
            assertSame(invocation, host.openedInvocation);
            assertEquals(2, world.writes);
            assertEquals(1, world.worldIdentityCreates);
            assertTrue(invocation.evidence.stream().anyMatch(value -> value.sourceId().equals("openallay_builder:write-readback")));
            assertTrue(invocation.evidence.stream().anyMatch(value -> value.sourceId().equals("openallay_builder:undo-readback")));
            invocation.successful = true;
        }
        assertEquals(1, world.closes);
        java.util.List<OperationJournal.Snapshot> journals = new OperationJournal(directory.resolve("journals")).list();
        assertEquals(2, journals.size());
        assertTrue(journals.stream().allMatch(journal -> journal.status() == OperationJournal.Status.COMPLETED));
        assertTrue(journals.stream().anyMatch(journal -> journal.entries().get(0).verified().blockEntity() != null
                && journal.entries().get(0).verified().blockEntity().contains("custom:gem")));
    }

    private static JsonObject evaluate(ExtensionContribution contribution, SdkFixture.Invocation invocation,
            String source) {
        Context context = Context.enter();
        try {
            context.setLanguageVersion(Context.VERSION_ES6);
            context.setOptimizationLevel(-1);
            context.setClassShutter(className -> false);
            ScriptableObject scope = new ImporterTopLevel(context);
            for (String name : new String[]{"Packages", "java", "javax", "org", "com", "edu", "net",
                    "Java", "JavaAdapter", "JavaImporter", "importClass", "importPackage", "getClass"})
                scope.delete(name);
            JavascriptHostBinding binding = contribution.hostBindings().get(0);
            assertEquals(BuilderBindings.MODULE, binding.id());
            Scriptable nativeModule = context.newObject(scope);
            for (JavascriptHostMethod method : binding.methods()) {
                BaseFunction function = new BaseFunction(scope, ScriptableObject.getFunctionPrototype(scope)) {
                    @Override public Object call(Context cx, Scriptable callScope, Scriptable thisObject, Object[] args) {
                        assertEquals(method.parameters().size(), args.length, method.name());
                        java.util.List<String> detached = new java.util.ArrayList<String>();
                        for (Object value : args)
                            detached.add(Context.toString(NativeJSON.stringify(cx, scope, value, null, null)));
                        try {
                            String result = method.invoker().invoke(invocation, detached);
                            return NativeJSON.parse(cx, scope, result, (c, s, self, values) -> values[1]);
                        } catch (Exception failure) {
                            throw Context.throwAsScriptRuntimeEx(failure);
                        }
                    }
                };
                ScriptableObject.putProperty(nativeModule, method.name(), function);
            }
            ScriptableObject.putProperty(scope, "fixtureNativeModule", nativeModule);
            JsonObject modules = new JsonObject();
            for (JavascriptModuleSource module : contribution.javascriptModules())
                modules.addProperty(module.id(), module.source());
            String loader = "var global=this,fixtureSources=JSON.parse(" + new JsonPrimitive(modules.toString()) + ");"
                    + "var fixtureModules={};function require(id){"
                    + "if(id==='openallay_builder:native')return fixtureNativeModule;"
                    + "if(Object.prototype.hasOwnProperty.call(fixtureModules,id))return fixtureModules[id].exports;"
                    + "if(!Object.prototype.hasOwnProperty.call(fixtureSources,id))throw new Error('Missing module: '+id);"
                    + "var module={exports:{}};fixtureModules[id]=module;"
                    + "var factory=eval('(function(exports,module,require){\\n'+fixtureSources[id]+'\\n})');"
                    + "factory(module.exports,module,require);return module.exports;}";
            Object result = context.evaluateString(scope,
                    loader + "JSON.stringify((function(){" + source + "})())", "builder-restricted-host-fixture", 1, null);
            return JsonParser.parseString(Context.toString(result)).getAsJsonObject();
        } finally { Context.exit(); }
    }
}
