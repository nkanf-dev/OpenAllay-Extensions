package dev.openallay.builder.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Independent application templates. No world/save files or native objects are used here. */
public final class TemplateStore {
    public static final String FORMAT = "openallay:structure";
    private final AtomicJsonFiles files;

    public TemplateStore(Path directory) throws IOException {
        files = new AtomicJsonFiles(directory);
    }

    TemplateStore(Path directory, AtomicJsonFiles.Publisher publisher) throws IOException {
        files = new AtomicJsonFiles(directory, publisher);
    }

    /** Validate and atomically replace a template. The caller retains no mutable reference into the store. */
    public void save(String name, JsonObject template) throws IOException {
        JsonObject copy = validate(template);
        files.write(name, copy);
    }

    public void save(String name, String json) throws IOException {
        save(name, StrictJson.parse(json));
    }

    /** Returns a newly detached JSON tree on every call. Unknown registry entries remain intact. */
    public JsonObject load(String name) throws IOException {
        try {
            return validate(files.read(name));
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid template " + name + ": " + e.getMessage(), e);
        }
    }

    /** Lists validated templates by sorted ID. Malformed files are errors, not silently omitted content. */
    public List<String> list() throws IOException {
        List<String> names = files.names();
        for (String name : names) load(name);
        return names;
    }

    public static JsonObject validate(JsonObject template) {
        Objects.requireNonNull(template, "template");
        JsonObject copy = template.deepCopy();
        StrictJson.fields(copy, Set.of("format", "size", "palette", "blocks", "metadata",
                "includesAir", "gameVersion", "dataVersion"), Set.of());
        if (!FORMAT.equals(StrictJson.string(copy.get("format"), "format")))
            throw StrictJson.invalid("Unsupported template format");
        StrictJson.nonBlank(StrictJson.string(copy.get("gameVersion"), "gameVersion"), "gameVersion");
        if (StrictJson.integer(copy.get("dataVersion"), "dataVersion") < 0)
            throw StrictJson.invalid("dataVersion must be nonnegative");
        boolean includesAir = StrictJson.bool(copy.get("includesAir"), "includesAir");
        BlockPosition size = BlockPosition.fromJson(copy.get("size"));
        if (size.x() <= 0 || size.y() <= 0 || size.z() <= 0)
            throw StrictJson.invalid("Template sizes must be positive");
        StrictJson.object(copy.get("metadata"), "metadata");
        JsonArray paletteJson = StrictJson.array(copy.get("palette"), "palette");
        List<BlockSpec> palette = new ArrayList<>();
        for (JsonElement value : paletteJson) {
            JsonObject item = StrictJson.object(value, "palette block");
            StrictJson.fields(item, Set.of("id", "properties"), Set.of());
            palette.add(BlockSpec.fromJson(item));
        }
        Set<BlockPosition> positions = new HashSet<>();
        for (JsonElement value : StrictJson.array(copy.get("blocks"), "blocks")) {
            JsonObject block = StrictJson.object(value, "block");
            StrictJson.fields(block, Set.of("pos", "state"), Set.of("blockEntity"));
            BlockPosition position = BlockPosition.fromJson(block.get("pos"));
            if (position.x() < 0 || position.y() < 0 || position.z() < 0
                    || position.x() >= size.x() || position.y() >= size.y() || position.z() >= size.z())
                throw StrictJson.invalid("Template position outside size: " + position);
            if (!positions.add(position)) throw StrictJson.invalid("Duplicate template position: " + position);
            int index = StrictJson.integer(block.get("state"), "state");
            if (index < 0 || index >= palette.size()) throw StrictJson.invalid("Palette index outside palette");
            if (!includesAir && palette.get(index).isAir())
                throw StrictJson.invalid("Air block conflicts with includesAir=false");
            if (block.has("blockEntity"))
                StrictJson.nonBlank(StrictJson.string(block.get("blockEntity"), "blockEntity"), "blockEntity SNBT");
        }
        // Validate arbitrary metadata as strict JSON as well (including nonfinite numeric values).
        StrictJson.parse(StrictJson.GSON.toJson(copy));
        return copy;
    }
}
