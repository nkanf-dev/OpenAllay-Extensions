package dev.openallay.builder;
import java.io.IOException;
/** Finalization after an already-started native commit must retain its actual outcome. */
final class OutcomePersistence {
    private OutcomePersistence() {}
    @FunctionalInterface interface IoAction { void run() throws IOException; }
    static void run(IoAction action) throws IOException {
        boolean interrupted=Thread.interrupted();
        try { action.run(); } finally { if(interrupted)Thread.currentThread().interrupt(); }
    }
}
