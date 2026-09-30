package dev.openallay.builder;
import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
class OutcomePersistenceTest {
    @TempDir Path directory;
    @Test void interruptedWorkerCanPublishExistingOutcomeWithoutDroppingInterrupt() throws Exception {
        Thread.currentThread().interrupt();
        try {
            OutcomePersistence.run(()->{
                assertFalse(Thread.currentThread().isInterrupted());
                Files.writeString(directory.resolve("result"),"actual-readback");
            });
            assertTrue(Thread.currentThread().isInterrupted());
        } finally { Thread.interrupted(); }
        assertEquals("actual-readback",Files.readString(directory.resolve("result")));
    }
}
