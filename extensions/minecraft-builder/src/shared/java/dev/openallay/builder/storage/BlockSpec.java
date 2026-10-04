package dev.openallay.builder.storage;

import com.google.gson.JsonObject;
import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/** A detached immutable block image. SNBT stays opaque until the native registry codec validates it. */
public final class BlockSpec {
    private final String id;
    private final Map<String, String> properties;
    private final String blockEntity;

    public BlockSpec(String id, Map<String, String> properties, String blockEntity) {
        id = StrictJson.identifier(id, "block id");
        Objects.requireNonNull(properties, "properties");
        TreeMap<String, String> copy = new TreeMap<>();
        properties.forEach((key, value) -> copy.put(StrictJson.nonBlank(key, "property name"),
                StrictJson.nonBlank(value, "property value")));
        properties = Collections.unmodifiableMap(copy);
        if (blockEntity != null) StrictJson.nonBlank(blockEntity, "blockEntity SNBT");
        this.id = id;
        this.properties = properties;
        this.blockEntity = blockEntity;
    }

    public String id() { return id; }
    public Map<String, String> properties() { return properties; }
    public String blockEntity() { return blockEntity; }

    @Override public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof BlockSpec)) return false;
        BlockSpec other = (BlockSpec) object;
        return Objects.equals(id, other.id)
                && Objects.equals(properties, other.properties)
                && Objects.equals(blockEntity, other.blockEntity);
    }

    @Override public int hashCode() {
        int result = Objects.hashCode(id);
        result = 31 * result + Objects.hashCode(properties);
        result = 31 * result + Objects.hashCode(blockEntity);
        return result;
    }

    @Override public String toString() {
        return "BlockSpec[id=" + id + ", properties=" + properties + ", blockEntity=" + blockEntity + "]";
    }

    public BlockSpec(String id, Map<String, String> properties) {
        this(id, properties, null);
    }

    public static BlockSpec fromJson(String json) {
        return fromJson(StrictJson.parse(json));
    }

    public static BlockSpec fromJson(JsonObject object) {
        Objects.requireNonNull(object, "block");
        StrictJson.fields(object, StrictJson.set("id", "properties"), StrictJson.set("blockEntity"));
        JsonObject values = StrictJson.object(object.get("properties"), "properties");
        Map<String, String> properties = new TreeMap<>();
        values.entrySet().forEach(entry -> properties.put(entry.getKey(),
                StrictJson.string(entry.getValue(), "property " + entry.getKey())));
        return new BlockSpec(StrictJson.string(object.get("id"), "id"), properties,
                object.has("blockEntity") ? StrictJson.string(object.get("blockEntity"), "blockEntity") : null);
    }

    public JsonObject toJson() {
        JsonObject object = new JsonObject();
        object.addProperty("id", id);
        JsonObject values = new JsonObject();
        properties.forEach(values::addProperty);
        object.add("properties", values);
        if (blockEntity != null) object.addProperty("blockEntity", blockEntity);
        return object;
    }

    public String toJsonString() {
        return StrictJson.GSON.toJson(toJson());
    }

    public boolean isAir() {
        return id.equals("minecraft:air") || id.equals("minecraft:cave_air") || id.equals("minecraft:void_air");
    }
}
