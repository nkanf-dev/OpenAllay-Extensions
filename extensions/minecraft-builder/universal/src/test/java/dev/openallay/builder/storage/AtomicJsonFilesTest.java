package dev.openallay.builder.storage;

import static org.junit.jupiter.api.Assertions.*;
import dev.openallay.builder.FixtureValues;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class AtomicJsonFilesTest {
    @TempDir Path directory;

    @Test void unsupportedDirectoryForceDoesNotPreventNormalAtomicStore() throws Exception {
        AtomicJsonFiles files = new AtomicJsonFiles(directory, AtomicJsonFiles.ATOMIC_MOVE,
                ignored -> { throw new UnsupportedOperationException("No directory force on this filesystem"); });
        com.google.gson.JsonObject first = StrictJson.parse("{\"value\":1}");
        com.google.gson.JsonObject second = StrictJson.parse("{\"value\":2}");
        files.write("document", first);
        assertEquals(first, files.read("document"));
        files.write("document", second);
        assertEquals(second, files.read("document"));
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Test void actualDirectoryIoFailurePropagatesEvenAfterAtomicPublication() throws Exception {
        AtomicJsonFiles files = new AtomicJsonFiles(directory, AtomicJsonFiles.ATOMIC_MOVE,
                ignored -> { throw new IOException("Injected directory disk failure"); });
        com.google.gson.JsonObject document = StrictJson.parse("{\"value\":1}");
        IOException error = assertThrows(IOException.class, () -> files.write("document", document));
        assertEquals("Injected directory disk failure", error.getMessage());
        // This is a durability failure after rename, not a claim that publication rolled back.
        assertEquals(document, files.read("document"));
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Test void unsupportedAtomicMoveFailsExplicitlyWithoutNonAtomicFallback() throws Exception {
        AtomicJsonFiles original = new AtomicJsonFiles(directory);
        com.google.gson.JsonObject first = StrictJson.parse("{\"value\":1}");
        original.write("document", first);
        AtomicJsonFiles unsupported = new AtomicJsonFiles(directory, (temporary, destination) -> {
            throw new AtomicMoveNotSupportedException(temporary.toString(), destination.toString(), "unsupported");
        });
        assertThrows(AtomicMoveNotSupportedException.class,
                () -> unsupported.write("document", StrictJson.parse("{\"value\":2}")));
        assertEquals(first, original.read("document"));
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }
    @Test void streamingWriterPreservesCurrentJsonShapeAndUnicode() throws Exception {
        AtomicJsonFiles files = new AtomicJsonFiles(directory);
        com.google.gson.JsonObject document = StrictJson.parse("{\"text\":\"<tag> 中文 \ud83d\udc9a\\n\",\"values\":[1,true,null]}");
        files.write("tree", document);
        files.write("stream", writer -> {
            writer.beginObject();
            writer.name("text").value(document.get("text").getAsString());
            writer.name("values").beginArray().value(1).value(true).nullValue().endArray();
            writer.endObject();
        });
        assertEquals(document, files.read("stream"));
        assertEquals(FixtureValues.readString(directory.resolve("tree.json")), FixtureValues.readString(directory.resolve("stream.json")));
    }

    @Test void streamingSerializationFailureNeverPublishesOrChangesPreviousDocument() throws Exception {
        java.util.concurrent.atomic.AtomicInteger publications = new java.util.concurrent.atomic.AtomicInteger();
        AtomicJsonFiles files = new AtomicJsonFiles(directory, (temporary, destination) -> {
            publications.incrementAndGet();
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        });
        com.google.gson.JsonObject original = StrictJson.parse("{\"value\":1}");
        files.write("document", original);
        assertThrows(IOException.class, () -> files.write("document", writer -> {
            writer.beginObject().name("value").value(2);
            throw new IOException("serializer failed before force");
        }));
        assertEquals(1, publications.get());
        assertEquals(original, files.read("document"));
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

    @Test void incompleteStreamAndDirectoryFailureKeepAtomicFailureSemantics() throws Exception {
        java.util.concurrent.atomic.AtomicInteger publications = new java.util.concurrent.atomic.AtomicInteger();
        AtomicJsonFiles files = new AtomicJsonFiles(directory, (temporary, destination) -> {
            publications.incrementAndGet();
            AtomicJsonFiles.ATOMIC_MOVE.publish(temporary, destination);
        });
        com.google.gson.JsonObject original = StrictJson.parse("{\"value\":1}");
        files.write("document", original);
        assertThrows(IOException.class, () -> files.write("document", writer -> writer.beginObject().name("value").value(2)));
        assertEquals(1, publications.get());
        assertEquals(original, files.read("document"));
        AtomicJsonFiles failingForce = new AtomicJsonFiles(directory, AtomicJsonFiles.ATOMIC_MOVE,
                ignored -> { throw new IOException("stream directory force failed after rename"); });
        assertThrows(IOException.class, () -> failingForce.write("document", writer -> writer.beginObject().name("value").value(3).endObject()));
        assertEquals(StrictJson.parse("{\"value\":3}"), files.read("document"));
        try (java.util.stream.Stream<Path> paths = Files.list(directory)) { assertEquals(1, paths.count()); }
    }

}
