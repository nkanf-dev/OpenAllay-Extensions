package dev.openallay.builder;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.math.BigDecimal;
import net.minecraft.core.BlockPos;

/** Inclusive region bounds. The context maxY alone is exclusive. */
record BuilderBounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
    BuilderBounds {
        if (minX > maxX || minY > maxY || minZ > maxZ)
            throw new BuilderException("invalid_bounds", "Region minima must not exceed maxima");
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
    BlockPos at(long index) {
        long width = (long)maxX - minX + 1;
        long depth = (long)maxZ - minZ + 1;
        return new BlockPos((int)(minX + index % width), (int)(minY + index / width / depth), (int)(minZ + index / width % depth));
    }
}
