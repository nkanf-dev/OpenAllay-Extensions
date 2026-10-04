package dev.openallay.builder.storage;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;

/** Detached coordinates. Template positions are relative; journal positions are absolute. */
public final class BlockPosition {
    private final int x;
    private final int y;
    private final int z;

    public BlockPosition(int x, int y, int z) {
        this.x = x;
        this.y = y;
        this.z = z;
    }

    public int x() { return x; }
    public int y() { return y; }
    public int z() { return z; }

    @Override public boolean equals(Object object) {
        if (this == object) return true;
        if (!(object instanceof BlockPosition)) return false;
        BlockPosition other = (BlockPosition) object;
        return x == other.x
                && y == other.y
                && z == other.z;
    }

    @Override public int hashCode() {
        int result = Integer.hashCode(x);
        result = 31 * result + Integer.hashCode(y);
        result = 31 * result + Integer.hashCode(z);
        return result;
    }

    @Override public String toString() {
        return "BlockPosition[x=" + x + ", y=" + y + ", z=" + z + "]";
    }

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
