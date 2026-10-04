package dev.openallay.builder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockPosition;
import java.math.BigDecimal;

/** Inclusive region bounds. The context maxY alone is exclusive. */
final class BuilderBounds {
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    BuilderBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        if (minX > maxX || minY > maxY || minZ > maxZ)
            throw new BuilderException("invalid_bounds", "Region minima must not exceed maxima");
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    public int minX() { return minX; }
    public int minY() { return minY; }
    public int minZ() { return minZ; }
    public int maxX() { return maxX; }
    public int maxY() { return maxY; }
    public int maxZ() { return maxZ; }

    @Override public boolean equals(Object other) {
        if (this == other) return true;
        if (!(other instanceof BuilderBounds)) return false;
        BuilderBounds value = (BuilderBounds) other;
        return minX == value.minX
                && minY == value.minY
                && minZ == value.minZ
                && maxX == value.maxX
                && maxY == value.maxY
                && maxZ == value.maxZ;
    }

    @Override public int hashCode() {
        int result = 0;
        result = 31 * result + minX;
        result = 31 * result + minY;
        result = 31 * result + minZ;
        result = 31 * result + maxX;
        result = 31 * result + maxY;
        result = 31 * result + maxZ;
        return result;
    }

    @Override public String toString() {
        return "BuilderBounds[minX=" + minX + ", minY=" + minY + ", minZ=" + minZ + ", maxX=" + maxX + ", maxY=" + maxY + ", maxZ=" + maxZ + "]";
    }

    static BuilderBounds parse(String json) {
        JsonObject object = JsonParser.parseString(json).getAsJsonObject();
        return new BuilderBounds(integer(object,"minX"), integer(object,"minY"), integer(object,"minZ"),
                integer(object,"maxX"), integer(object,"maxY"), integer(object,"maxZ"));
    }
    static int integer(JsonObject object, String key) {
        try { return new BigDecimal(object.get(key).getAsString()).intValueExact(); }
        catch (RuntimeException invalid) { throw new BuilderException("invalid_coordinate", "Expected exact 32-bit integer: " + key, invalid); }
    }
    long volume() {
        try { return Math.multiplyExact(Math.multiplyExact((long)maxX - minX + 1, (long)maxY - minY + 1), (long)maxZ - minZ + 1); }
        catch (ArithmeticException overflow) { throw new BuilderException("invalid_bounds", "Region volume exceeds 64-bit addressing", overflow); }
    }
    BlockPosition at(long index) {
        long width = (long)maxX - minX + 1;
        long depth = (long)maxZ - minZ + 1;
        return new BlockPosition((int)(minX + index % width), (int)(minY + index / width / depth), (int)(minZ + index / width % depth));
    }
}
