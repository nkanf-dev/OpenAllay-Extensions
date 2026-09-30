package dev.openallay.builder.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/** Detached coordinates. Template positions are relative; journal positions are absolute. */
public record BlockPosition(int x, int y, int z) {
    public JsonArray toJson() {
        JsonArray result = new JsonArray();
        result.add(x);
        result.add(y);
        result.add(z);
        return result;
    }

    public static BlockPosition fromJson(JsonElement value) {
        JsonArray array = StrictJson.array(value, "position");
        if (array.size() != 3) throw StrictJson.invalid("position must have exactly three coordinates");
        return new BlockPosition(StrictJson.integer(array.get(0), "x"),
                StrictJson.integer(array.get(1), "y"), StrictJson.integer(array.get(2), "z"));
    }
}
