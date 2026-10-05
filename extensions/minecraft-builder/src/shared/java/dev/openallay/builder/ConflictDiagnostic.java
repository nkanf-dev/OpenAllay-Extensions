package dev.openallay.builder;

import com.google.gson.JsonObject;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.logging.Logger;
import dev.openallay.builder.storage.BlockPosition;

/** Private, single-conflict evidence. Never returned through status, evidence or host bindings. */
final class ConflictDiagnostic {
    static final String LOGGER_NAME = "dev.openallay.builder.conflict";
    static final String PREFIX = "OPENALLAY_BUILDER_CONFLICT ";
    static final int IMAGE_LIMIT = 16384;
    private final JsonObject witness = new JsonObject();
    private boolean emitted;

    ConflictDiagnostic(String phase, BlockPosition position, String before, String intended,
            String current, String correlation, String operationId, String worldId, String dimension,
            boolean ownerThread, boolean workerThread) {
        witness.addProperty("code", "concurrent_edit");
        witness.addProperty("phase", phase);
        witness.addProperty("intendedValidated", !"write-preflight".equals(phase));
        JsonObject point = new JsonObject();
        point.addProperty("x", position.x()); point.addProperty("y", position.y()); point.addProperty("z", position.z());
        witness.add("position", point);
        witness.add("before", image(before)); witness.add("intended", image(intended)); witness.add("current", image(current));
        witness.add("correlationId", image(correlation, 256));
        witness.add("operationId", image(operationId, 256));
        witness.add("worldId", image(worldId, 256)); witness.add("dimension", image(dimension, 256));
        witness.addProperty("ownerThread", ownerThread); witness.addProperty("workerThreadAtCapture", workerThread);
        witness.addProperty("boundary", "admitted-owner-action;position-read-validated;optimistic-gate-before-mutation");
    }

    void emit() {
        if (emitted) return;
        emitted = true;
        // Diagnostics must not change failure code, journal completion or conflict protection.
        try { Logger.getLogger(LOGGER_NAME).warning(PREFIX + witness.toString()); }
        catch (RuntimeException ignored) { /* Keep the original domain failure. */ }
    }

    private static JsonObject image(String value) { return image(value, IMAGE_LIMIT); }
    private static JsonObject image(String value, int limit) {
        JsonObject result = new JsonObject();
        if (value == null) { result.addProperty("available", false); return result; }
        result.addProperty("available", true);
        result.addProperty("characters", value.length());
        result.addProperty("sha256", sha256(value));
        boolean complete = value.length() <= limit;
        result.addProperty("complete", complete);
        if (complete) result.addProperty("json", value);
        return result;
    }

    private static String sha256(String value) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            // Bound temporary encoding allocations, including oversized block-entity images.
            for (int start = 0; start < value.length();) {
                int end = Math.min(value.length(), start + 4096);
                if (end < value.length() && Character.isHighSurrogate(value.charAt(end - 1))) end--;
                digest.update(value.substring(start, end).getBytes(StandardCharsets.UTF_8));
                start = end;
            }
            StringBuilder hex = new StringBuilder(64);
            for (byte b : digest.digest()) hex.append(String.format(java.util.Locale.ROOT, "%02x", b & 255));
            return hex.toString();
        } catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
}
