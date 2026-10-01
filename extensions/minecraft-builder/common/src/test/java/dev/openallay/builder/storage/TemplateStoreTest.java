package dev.openallay.builder.storage;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemplateStoreTest {
    @TempDir Path directory;

    static JsonObject template() {
        return StrictJson.parse("""
                {"format":"openallay:structure","size":[2,3,4],
                 "palette":[{"id":"example:storage","properties":{"facing":"west","waterlogged":"false"}},
                            {"id":"minecraft:air","properties":{}}],
                 "blocks":[{"pos":[1,2,3],"state":0,"blockEntity":"{Items:[{Slot:0b,id:'minecraft:diamond',count:2}],CustomName:'箱'}"},
                           {"pos":[0,0,0],"state":1}],
                 "metadata":{"author":"test","unknown":{"array":[1,true,null]}},
                 "includesAir":true,"gameVersion":"26.2","dataVersion":5000}
                """);
    }

    @Test void roundTripPreservesUnknownRegistryPropertiesSnbtAndMetadataWithoutMutableAliases() throws Exception {
        TemplateStore store = new TemplateStore(directory);
        JsonObject original = template();
        JsonObject expected = original.deepCopy();
        store.save("模板-1", original);
        original.getAsJsonObject("metadata").addProperty("mutated", true);
        JsonObject loaded = store.load("模板-1");
        assertEquals(expected, loaded);
        loaded.getAsJsonArray("palette").get(0).getAsJsonObject().addProperty("id", "minecraft:stone");
        assertEquals(expected, store.load("模板-1"));
        assertEquals(List.of("模板-1"), store.list());
    }

    @Test void listIsSortedAndDoesNotTreatTemporaryFilesAsPublished() throws Exception {
        TemplateStore store = new TemplateStore(directory);
        store.save("z", template());
        store.save("a", template());
        Files.writeString(directory.resolve(".pending-aborted.tmp"), "truncated");
        assertEquals(List.of("a", "z"), store.list());
        assertThrows(UnsupportedOperationException.class, () -> store.list().add("another"));
    }

    @Test void rejectsTraversalAbsoluteNamesAndFinalSymlinks() throws Exception {
        TemplateStore store = new TemplateStore(directory.resolve("templates"));
        for (String name : List.of("../outside", "..", ".", "/absolute", "a/b", "a\\b", "", "a:ads", ".hidden")) {
            assertThrows(IllegalArgumentException.class, () -> store.save(name, template()), name);
        }
        Path outside = directory.resolve("outside.json");
        Files.writeString(outside, "untouched");
        Files.createSymbolicLink(directory.resolve("templates/link.json"), outside);
        assertThrows(IOException.class, () -> store.save("link", template()));
        assertThrows(IOException.class, () -> store.load("link"));
        assertThrows(IOException.class, store::list);
        assertEquals("untouched", Files.readString(outside));
    }

    @Test void rejectsSymbolicLinkRoot() throws Exception {
        Path actual = Files.createDirectory(directory.resolve("actual"));
        Path link = directory.resolve("link");
        Files.createSymbolicLink(link, actual);
        assertThrows(IOException.class, () -> new TemplateStore(link));
    }

    @Test void rejectsCorruptionDuplicateKeysTrailingContentAndUnknownFields() throws Exception {
        TemplateStore store = new TemplateStore(directory);
        for (String text : List.of("{", "{} garbage", "{\"format\":\"a\",\"format\":\"b\"}",
                "{\"value\":NaN}", "{\"value\":1,}")) {
            Files.writeString(directory.resolve("bad.json"), text);
            assertThrows(IOException.class, () -> store.load("bad"), text);
        }
        JsonObject future = template();
        future.addProperty("unknownField", 2);
        Files.writeString(directory.resolve("bad.json"), future.toString());
        assertThrows(IOException.class, () -> store.load("bad"));
        assertThrows(IOException.class, store::list);
        assertEquals(future.toString(), Files.readString(directory.resolve("bad.json")));
    }

    @Test void validatesSchemaTypesFieldsBoundsPaletteAndAirPolicy() throws Exception {
        TemplateStore store = new TemplateStore(directory);
        for (String field : List.of("format", "size", "palette", "blocks", "metadata", "includesAir",
                "gameVersion", "dataVersion")) {
            JsonObject value = template();
            value.remove(field);
            assertThrows(IllegalArgumentException.class, () -> store.save("bad", value), field);
        }
        JsonObject unknown = template(); unknown.addProperty("extra", true);
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", unknown));
        JsonObject fractional = template(); fractional.addProperty("dataVersion", 1.5);
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", fractional));
        JsonObject numericString = template(); numericString.addProperty("dataVersion", "5000");
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", numericString));
        JsonObject negativeVersion = template(); negativeVersion.addProperty("dataVersion", -1);
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", negativeVersion));
        JsonObject badBoolean = template(); badBoolean.addProperty("includesAir", "true");
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", badBoolean));
        JsonObject air = template(); air.addProperty("includesAir", false);
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", air));
        JsonObject duplicate = template(); duplicate.getAsJsonArray("blocks").add(duplicate.getAsJsonArray("blocks").get(0));
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", duplicate));
        JsonObject index = template(); index.getAsJsonArray("blocks").get(0).getAsJsonObject().addProperty("state", 2);
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", index));
        JsonObject outside = template(); outside.getAsJsonArray("blocks").get(0).getAsJsonObject()
                .add("pos", StrictJson.parse("{\"v\":[-1,0,0]}").get("v"));
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", outside));
        JsonObject blockEntityInPalette = template(); blockEntityInPalette.getAsJsonArray("palette").get(0)
                .getAsJsonObject().addProperty("blockEntity", "{}");
        assertThrows(IllegalArgumentException.class, () -> store.save("bad", blockEntityInPalette));
    }

    @Test void atomicPublicationFailurePreservesOldDocumentAndCleansTemporaryFile() throws Exception {
        TemplateStore original = new TemplateStore(directory);
        JsonObject old = template();
        original.save("house", old);
        TemplateStore failing = new TemplateStore(directory, (temporary, destination) -> {
            assertEquals(old, original.load("house"));
            assertTrue(Files.size(temporary) > 0);
            throw new IOException("Injected publication failure");
        });
        JsonObject replacement = template(); replacement.addProperty("gameVersion", "future-game");
        assertThrows(IOException.class, () -> failing.save("house", replacement));
        assertEquals(old, original.load("house"));
        try (var stream = Files.list(directory)) { assertEquals(List.of("house.json"), stream.map(p -> p.getFileName().toString()).toList()); }
        assertThrows(IOException.class, () -> failing.save("new-house", replacement));
        assertFalse(Files.exists(directory.resolve("new-house.json")));
    }

    @Test void sparseLargeTemplateHasNoArtificialVolumeLimitOrIntegerMultiplicationOverflow() throws Exception {
        JsonObject value = template();
        value.add("size", StrictJson.parse("{\"v\":[2147483647,2147483647,2147483647]}").get("v"));
        TemplateStore store = new TemplateStore(directory);
        store.save("large", value);
        assertEquals(value, store.load("large"));
    }

    @Test void blockImageRequiresNamespacedIdsExactTypesAndCopiesProperties() {
        var properties = new java.util.HashMap<>(Map.of("facing", "east"));
        BlockSpec image = new BlockSpec("example:block", properties, "{foo:1b}");
        properties.put("facing", "west");
        assertEquals("east", image.properties().get("facing"));
        assertThrows(UnsupportedOperationException.class, () -> image.properties().put("foo", "bar"));
        assertEquals(image, BlockSpec.fromJson(image.toJsonString()));
        assertThrows(IllegalArgumentException.class, () -> new BlockSpec("stone", Map.of()));
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.fromJson("{\"id\":\"a:b\",\"properties\":{\"foo\":true}}"));
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.fromJson("{\"id\":\"a:b\",\"id\":\"c:d\",\"properties\":{}}"));
        assertThrows(IllegalArgumentException.class, () -> BlockSpec.fromJson("{\"id\":\"a:b\",\"properties\":{},\"blockEntity\":null}"));
    }
}
