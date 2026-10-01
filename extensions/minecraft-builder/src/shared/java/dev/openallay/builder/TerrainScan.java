package dev.openallay.builder;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockSpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.minecraft.core.BlockPos;

/** Detached scan commands and resumable owner work. Classification belongs to the Extension. */
final class TerrainScan {
    static final Set<String> AIR = Set.of("minecraft:air", "minecraft:cave_air", "minecraft:void_air");
    private static final Set<String> LIQUID = Set.of("minecraft:water", "minecraft:lava", "minecraft:bubble_column");

    record Request(int minX, int minZ, int maxX, int maxZ, int minY, int maxY,
                   boolean groundOnly, Set<String> ground, Set<String> vegetation) {
        Request {
            if (minX > maxX || minZ > maxZ || minY >= maxY)
                throw new BuilderException("invalid_bounds", "Scan minima must not exceed maxima; maxY is exclusive");
            ground = Set.copyOf(ground);
            vegetation = Set.copyOf(vegetation);
        }

        static Request parse(String json) {
            JsonObject value = JsonParser.parseString(json).getAsJsonObject();
            return new Request(BuilderBounds.integer(value,"minX"), BuilderBounds.integer(value,"minZ"),
                    BuilderBounds.integer(value,"maxX"), BuilderBounds.integer(value,"maxZ"),
                    BuilderBounds.integer(value,"minY"), BuilderBounds.integer(value,"maxY"),
                    value.get("groundOnly").getAsBoolean(), ids(value,"ground"), ids(value,"vegetation"));
        }

        private static Set<String> ids(JsonObject object, String name) {
            Set<String> result = new HashSet<>();
            for (JsonElement item : object.getAsJsonArray(name)) result.add(item.getAsString());
            return Set.copyOf(result);
        }

        long columns() {
            try { return Math.multiplyExact((long)maxX - minX + 1, (long)maxZ - minZ + 1); }
            catch (ArithmeticException overflow) { throw new BuilderException("invalid_bounds", "Scan columns exceed 64-bit addressing", overflow); }
        }

        int x(long index) { return (int)(minX + index / ((long)maxZ - minZ + 1)); }
        int z(long index) { return (int)(minZ + index % ((long)maxZ - minZ + 1)); }

        boolean accepts(BlockSpec block) {
            if (AIR.contains(block.id())) return false;
            if (!groundOnly) return true;
            if (LIQUID.contains(block.id()) || "true".equals(block.properties().get("waterlogged"))) return false;
            String id = block.id();
            boolean tree = id.startsWith("minecraft:") && !id.startsWith("minecraft:stripped_") &&
                    (id.endsWith("_log") || id.endsWith("_leaves") || id.endsWith("_sapling")) &&
                    !"true".equals(block.properties().get("persistent"));
            return !vegetation.contains(id) && !tree && ground.contains(id);
        }
    }

    record Column(int x, int z, Integer y, BlockSpec block) {
        JsonObject json() {
            JsonObject value = new JsonObject();
            value.addProperty("x",x); value.addProperty("z",z);
            if (y == null) {
                value.add("y",JsonNull.INSTANCE); value.add("block",JsonNull.INSTANCE); value.add("properties",JsonNull.INSTANCE);
            } else {
                value.addProperty("y",y); value.addProperty("block",block.id());
                JsonObject properties = new JsonObject();
                block.properties().forEach(properties::addProperty);
                value.add("properties",properties);
            }
            return value;
        }
    }

    record Slice(List<Column> columns, long reads) {
        Slice { columns = List.copyOf(columns); }
    }

    /** A cursor crosses owner actions with integers only, never live world objects. */
    static final class Cursor {
        private final Request request;
        private final long count;
        private long index;
        private int y;
        private boolean started;

        Cursor(Request request) { this.request = request; count = request.columns(); }
        boolean done() { return index == count; }

        Slice capture(BuilderBackend source, int quantum) {
            List<Column> result = new ArrayList<>();
            long reads = 0;
            int work = 0;
            long deadline = source.sliceDeadline();
            while (!done() && work < quantum && (work == 0 || System.nanoTime() < deadline)) {
                int x = request.x(index), z = request.z(index);
                if (!started) {
                    // A loaded-column bound only skips proven air, not rejected surface blocks.
                    y = Math.min(request.maxY() - 1, source.terrainTop(x,z,request.minY(),request.maxY()));
                    started = true;
                    work++; // Empty columns are bounded work too.
                    if (work == quantum && y >= request.minY()) break;
                }
                if (y < request.minY()) {
                    result.add(new Column(x,z,null,null));
                    index++; started = false;
                    continue;
                }
                BlockSpec block = source.terrainState(new BlockPos(x,y,z));
                if (block == null) throw new BuilderException("unobserved_block", "Unknown or unloaded block at " + x + "," + y + "," + z);
                reads++; work++;
                if (request.accepts(block)) {
                    result.add(new Column(x,z,y,block));
                    index++; started = false;
                } else if (y == request.minY()) {
                    result.add(new Column(x,z,null,null));
                    index++; started = false;
                } else y--;
            }
            return new Slice(result,reads);
        }
    }

    private TerrainScan() {}
}
