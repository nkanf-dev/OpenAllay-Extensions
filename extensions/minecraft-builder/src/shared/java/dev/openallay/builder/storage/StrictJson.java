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
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
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
        switch (reader.peek()) {
            case BEGIN_OBJECT: {
                JsonObject object = new JsonObject();
                reader.beginObject();
                while (reader.hasNext()) {
                    String key = reader.nextName();
                    if (object.has(key)) throw new IOException("Duplicate JSON member: " + key);
                    object.add(key, readValue(reader));
                }
                reader.endObject();
                return object;
            }
            case BEGIN_ARRAY: {
                JsonArray array = new JsonArray();
                reader.beginArray();
                while (reader.hasNext()) array.add(readValue(reader));
                reader.endArray();
                return array;
            }
            case STRING: return new JsonPrimitive(reader.nextString());
            case NUMBER: return new JsonPrimitive(new BigDecimal(reader.nextString()));
            case BOOLEAN: return new JsonPrimitive(reader.nextBoolean());
            case NULL:
                reader.nextNull();
                return JsonNull.INSTANCE;
            default: throw new IOException("Expected JSON value at " + reader.getPath());
        }
    }

    /** Java 8 equivalents of null-rejecting immutable collection factories. */
    static Set<String> set(String... values) {
        Set<String> copy = new HashSet<>();
        for (String value : values) {
            if (!copy.add(Objects.requireNonNull(value)))
                throw new IllegalArgumentException("Duplicate set element: " + value);
        }
        return Collections.unmodifiableSet(copy);
    }

    static <T> List<T> copyList(Collection<? extends T> values) {
        Objects.requireNonNull(values);
        List<T> copy = new ArrayList<>(values.size());
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableList(copy);
    }

    static <T> Set<T> copySet(Collection<? extends T> values) {
        Objects.requireNonNull(values);
        Set<T> copy = new HashSet<>();
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableSet(copy);
    }

    static <K, V> Map<K, V> copyMap(Map<? extends K, ? extends V> values) {
        Objects.requireNonNull(values);
        Map<K, V> copy = new HashMap<>();
        for (Map.Entry<? extends K, ? extends V> entry : values.entrySet())
            copy.put(Objects.requireNonNull(entry.getKey()), Objects.requireNonNull(entry.getValue()));
        return Collections.unmodifiableMap(copy);
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
        if (value == null || isBlank(value)) throw invalid(name + " must not be blank");
        return value;
    }

    private static boolean isBlank(String value) {
        for (int offset = 0; offset < value.length();) {
            int codePoint = value.codePointAt(offset);
            if (!Character.isWhitespace(codePoint)) return false;
            offset += Character.charCount(codePoint);
        }
        return true;
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
