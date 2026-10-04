package dev.openallay.builder;

import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.BlockSpec;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Detached scan commands and resumable owner work. Classification belongs to the Extension. */
final class TerrainScan {
    static final Set<String> AIR = immutableSet(Arrays.asList("minecraft:air", "minecraft:cave_air", "minecraft:void_air"));
    private static final Set<String> LIQUID = immutableSet(Arrays.asList("minecraft:water", "minecraft:lava", "minecraft:bubble_column"));

    static final class Request {
        private final int minX;
        private final int minZ;
        private final int maxX;
        private final int maxZ;
        private final int minY;
        private final int maxY;
        private final boolean groundOnly;
        private final Set<String> ground;
        private final Set<String> vegetation;

        Request(int minX, int minZ, int maxX, int maxZ, int minY, int maxY, boolean groundOnly, Set<String> ground, Set<String> vegetation) {
            if (minX > maxX || minZ > maxZ || minY >= maxY)
                throw new BuilderException("invalid_bounds", "Scan minima must not exceed maxima; maxY is exclusive");
            this.minX = minX;
            this.minZ = minZ;
            this.maxX = maxX;
            this.maxZ = maxZ;
            this.minY = minY;
            this.maxY = maxY;
            this.groundOnly = groundOnly;
            this.ground = immutableSet(ground);
            this.vegetation = immutableSet(vegetation);
        }

        public int minX() { return minX; }
        public int minZ() { return minZ; }
        public int maxX() { return maxX; }
        public int maxZ() { return maxZ; }
        public int minY() { return minY; }
        public int maxY() { return maxY; }
        public boolean groundOnly() { return groundOnly; }
        public Set<String> ground() { return ground; }
        public Set<String> vegetation() { return vegetation; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Request)) return false;
            Request value = (Request) other;
            return minX == value.minX
                    && minZ == value.minZ
                    && maxX == value.maxX
                    && maxZ == value.maxZ
                    && minY == value.minY
                    && maxY == value.maxY
                    && groundOnly == value.groundOnly
                    && Objects.equals(ground, value.ground)
                    && Objects.equals(vegetation, value.vegetation);
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + minX;
            result = 31 * result + minZ;
            result = 31 * result + maxX;
            result = 31 * result + maxZ;
            result = 31 * result + minY;
            result = 31 * result + maxY;
            result = 31 * result + Boolean.hashCode(groundOnly);
            result = 31 * result + Objects.hashCode(ground);
            result = 31 * result + Objects.hashCode(vegetation);
            return result;
        }

        @Override public String toString() {
            return "Request[minX=" + minX + ", minZ=" + minZ + ", maxX=" + maxX + ", maxZ=" + maxZ + ", minY=" + minY + ", maxY=" + maxY + ", groundOnly=" + groundOnly + ", ground=" + ground + ", vegetation=" + vegetation + "]";
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
            return immutableSet(result);
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

    static final class Column {
        private final int x;
        private final int z;
        private final Integer y;
        private final BlockSpec block;

        Column(int x, int z, Integer y, BlockSpec block) {
            this.x = x;
            this.z = z;
            this.y = y;
            this.block = block;
        }

        public int x() { return x; }
        public int z() { return z; }
        public Integer y() { return y; }
        public BlockSpec block() { return block; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Column)) return false;
            Column value = (Column) other;
            return x == value.x
                    && z == value.z
                    && Objects.equals(y, value.y)
                    && Objects.equals(block, value.block);
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + x;
            result = 31 * result + z;
            result = 31 * result + Objects.hashCode(y);
            result = 31 * result + Objects.hashCode(block);
            return result;
        }

        @Override public String toString() {
            return "Column[x=" + x + ", z=" + z + ", y=" + y + ", block=" + block + "]";
        }

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

    static final class Slice {
        private final List<Column> columns;
        private final long reads;

        Slice(List<Column> columns, long reads) {
            this.columns = immutableList(columns);
            this.reads = reads;
        }

        public List<Column> columns() { return columns; }
        public long reads() { return reads; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Slice)) return false;
            Slice value = (Slice) other;
            return Objects.equals(columns, value.columns)
                    && reads == value.reads;
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(columns);
            result = 31 * result + Long.hashCode(reads);
            return result;
        }

        @Override public String toString() {
            return "Slice[columns=" + columns + ", reads=" + reads + "]";
        }
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
                BlockSpec block = source.terrainState(new BlockPosition(x,y,z));
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

    private static <T> List<T> immutableList(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        for (T value : copy) Objects.requireNonNull(value);
        return Collections.unmodifiableList(copy);
    }

    private static <T> Set<T> immutableSet(Collection<T> values) {
        Set<T> copy = new HashSet<>();
        for (T value : values) copy.add(Objects.requireNonNull(value));
        return Collections.unmodifiableSet(copy);
    }
    private TerrainScan() {}
}
