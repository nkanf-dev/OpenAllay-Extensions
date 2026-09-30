package dev.openallay.builder;

/** Shared terminal-state rules used by the live controller and deterministic lifecycle tests. */
final class SessionLifecycle {
    private SessionLifecycle() {}
    static void requireWritable(String state) {
        if (state.startsWith("failed") || state.startsWith("cancelled") || state.equals("closed"))
            throw new BuilderException("operation_terminal", "Builder operation is terminal: " + state);
    }
    static void requireCompletable(String state) { requireWritable(state); }
    static String cancelled(long writes, boolean hasDurableIntent) {
        // Pending intent may have reached the game even if readback was interrupted.
        return writes > 0 || hasDurableIntent ? "cancelled-partial" : "cancelled";
    }
    static String failed(boolean revoked, long writes, boolean hasDurableIntent) {
        if (revoked) return cancelled(writes,hasDurableIntent);
        return writes > 0 || hasDurableIntent ? "failed-partial" : "failed";
    }
}
