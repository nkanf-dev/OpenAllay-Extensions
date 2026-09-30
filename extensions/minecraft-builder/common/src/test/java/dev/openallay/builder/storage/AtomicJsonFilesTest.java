package dev.openallay.builder.storage;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicJsonFilesTest {
    @TempDir Path directory;

    @Test void unsupportedDirectoryForceDoesNotPreventNormalAtomicStore() throws Exception {
        var files = new AtomicJsonFiles(directory, AtomicJsonFiles.ATOMIC_MOVE,
                ignored -> { throw new UnsupportedOperationException("No directory force on this filesystem"); });
        var first = StrictJson.parse("{\"value\":1}");
        var second = StrictJson.parse("{\"value\":2}");
        files.write("document", first);
        assertEquals(first, files.read("document"));
        files.write("document", second);
        assertEquals(second, files.read("document"));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Test void actualDirectoryIoFailurePropagatesEvenAfterAtomicPublication() throws Exception {
        var files = new AtomicJsonFiles(directory, AtomicJsonFiles.ATOMIC_MOVE,
                ignored -> { throw new IOException("Injected directory disk failure"); });
        var document = StrictJson.parse("{\"value\":1}");
        IOException error = assertThrows(IOException.class, () -> files.write("document", document));
        assertEquals("Injected directory disk failure", error.getMessage());
        // This is a durability failure after rename, not a claim that publication rolled back.
        assertEquals(document, files.read("document"));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Test void unsupportedAtomicMoveFailsExplicitlyWithoutNonAtomicFallback() throws Exception {
        var original = new AtomicJsonFiles(directory);
        var first = StrictJson.parse("{\"value\":1}");
        original.write("document", first);
        var unsupported = new AtomicJsonFiles(directory, (temporary, destination) -> {
            throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "unsupported");
        });
        assertThrows(AtomicMoveNotSupportedException.class,
                () -> unsupported.write("document", StrictJson.parse("{\"value\":2}")));
        assertEquals(first, original.read("document"));
        try (var paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }
}
