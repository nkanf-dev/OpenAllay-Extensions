package dev.openallay.builder.storage;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import java.io.IOException;
import java.io.Reader;
import java.io.StringReader;
import java.math.BigDecimal;
import java.util.HashSet;
import java.util.Set;

/** Schema helpers. Unlike JsonParser, this reader rejects duplicate object members. */
final class StrictJson {
    static final Gson GSON = new GsonBuilder().disableHtmlEscaping().create();

    private StrictJson() {}

    static JsonObject read(Reader source) throws IOException {
        JsonReader reader = new JsonReader(source);
        reader.setStrictness(Strictness.STRICT);
        JsonElement result = readValue(reader);
        if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("Trailing JSON content");
        if (!result.isJsonObject()) throw new IOException("Document must be a JSON object");
        return result.getAsJsonObject();
    }

    static JsonObject parse(String text) {
        if (text == null) throw invalid("JSON must not be null");
        try {
            return read(new StringReader(text));
        } catch (IOException | NumberFormatException e) {
            throw new IllegalArgumentException("Invalid JSON: " + e.getMessage(), e);
        }
    }

    private static JsonElement readValue(JsonReader reader) throws IOException {
        return switch (reader.peek()) {
            case BEGIN_OBJECT -> {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON member: " + key);
                    object.add(key, readValue(reader));
                }
                reader.endObject();
                yield object;
            }
            case BEGIN_ARRAY -> {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader));
                reader.endArray();
                yield array;
            }
            case STRING -> new JsonPrimitive(reader.nextString());
            case NUMBER -> new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN -> new JsonPrimitive(reader.nextBoolean());
            case NULL -> { reader.nextNull(); yield JsonNull.INSTANCE; }
            default -> throw new IOException("Expected JSON value at " + reader.getPath());
        };
    }

    static void fields(JsonObject object, Set<String> required, Set<String> optional) {
        Set<String> missing = new HashSet<>(required);
        missing.removeAll(object.keySet());
        if (!missing.isEmpty()) throw invalid("Missing fields: " + missing);
        Set<String> extra = new HashSet<>(object.keySet());
        extra.removeAll(required);
        extra.removeAll(optional);
        if (!extra.isEmpty()) throw invalid("Unknown fields: " + extra);
    }

    static JsonObject object(JsonElement value, String name) {
        if (value == null || !value.isJsonObject()) throw invalid(name + " must be an object");
        return value.getAsJsonObject();
    }

    static JsonArray array(JsonElement value, String name) {
        if (value == null || !value.isJsonArray()) throw invalid(name + " must be an array");
        return value.getAsJsonArray();
    }

    static String string(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw invalid(name + " must be a string");
        return value.getAsString();
    }

    static boolean bool(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw invalid(name + " must be a boolean");
        return value.getAsBoolean();
    }

    static long longInteger(JsonElement value, String name) {
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw invalid(name + " must be an integer");
        try {
            return value.getAsBigDecimal().longValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw invalid(name + " must be an exact signed 64-bit integer");
        }
    }

    static int integer(JsonElement value, String name) {
        long result = longInteger(value, name);
        if (result < Integer.MIN_VALUE || result > Integer.MAX_VALUE)
            throw invalid(name + " must be an exact signed 32-bit integer");
        return (int) result;
    }

    static String nonBlank(String value, String name) {
        if (value == null || value.isBlank()) throw invalid(name + " must not be blank");
        return value;
    }

    static String identifier(String value, String name) {
        if (value == null || !value.matches("[a-z0-9_.-]+:[a-z0-9/._-]+"))
            throw invalid(name + " must be a namespaced Minecraft identifier");
        return value;
    }

    static IllegalArgumentException invalid(String message) {
        return new IllegalArgumentException(message);
    }
}
