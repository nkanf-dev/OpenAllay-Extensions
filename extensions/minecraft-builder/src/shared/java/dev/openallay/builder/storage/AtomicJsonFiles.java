package dev.openallay.builder.storage;

import com.google.gson.JsonObject;
import com.google.gson.Strictness;
import com.google.gson.stream.JsonWriter;
import java.io.BufferedWriter;
import java.io.FilterOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/** Application-artifact IO only. Callers must keep this class off game-owner threads. */
final class AtomicJsonFiles {
    @FunctionalInterface
    interface Publisher {
        void publish(Path temporary, Path destination) throws IOException;
    }

    @FunctionalInterface
    interface DirectorySync {
        void force(Path directory) throws IOException;
    }

    /** Emits one complete JSON object without a full-document String or UTF-8 byte array. */
    @FunctionalInterface
    interface JsonContent {
        void write(JsonWriter writer) throws IOException;
    }

    static final Publisher ATOMIC_MOVE = (temporary, destination) -> Files.move(temporary, destination,
            StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    private static final DirectorySync DIRECTORY_SYNC = root -> {
        if (root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            try (FileChannel directory = FileChannel.open(root, StandardOpenOption.READ)) {
                directory.force(true);
            }
        }
    };

    private final Path root;
    private final Publisher publisher;
    private final DirectorySync directorySync;

    AtomicJsonFiles(Path directory) throws IOException {
        this(directory, ATOMIC_MOVE);
    }

    AtomicJsonFiles(Path directory, Publisher publisher) throws IOException {
        this(directory, publisher, DIRECTORY_SYNC);
    }

    AtomicJsonFiles(Path directory, Publisher publisher, DirectorySync directorySync) throws IOException {
        Path requested = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        if (Files.isSymbolicLink(requested)) throw new IOException("Artifact directory must not be a symbolic link");
        Files.createDirectories(requested);
        this.root = requested.toRealPath();
        this.publisher = Objects.requireNonNull(publisher, "publisher");
        this.directorySync = Objects.requireNonNull(directorySync, "directorySync");
    }

    static String name(String name) {
        if (name == null || !name.matches("[\\p{L}\\p{N}_-][\\p{L}\\p{N}_.-]*"))
            throw new IllegalArgumentException("Artifact name must contain letters, digits, underscore, hyphen or dot; "
                    + "it must not start with a dot");
        return name;
    }

    private void checkDirectory() throws IOException {
        if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !root.toRealPath().equals(root)) throw new IOException("Artifact directory identity changed");
    }

    private Path path(String name) throws IOException {
        checkDirectory();
        Path result = root.resolve(name(name) + ".json");
        if (Files.exists(result, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(result, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Artifact is not a regular file: " + name);
        return result;
    }

    synchronized boolean exists(String name) throws IOException {
        return Files.exists(path(name), LinkOption.NOFOLLOW_LINKS);
    }

    synchronized JsonObject read(String name) throws IOException {
        Path source = path(name);
        // NOFOLLOW_LINKS avoids following a swapped final path at open time.
        try (var channel = Files.newByteChannel(source, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
             Reader reader = java.nio.channels.Channels.newReader(channel, StandardCharsets.UTF_8)) {
            return StrictJson.read(reader);
        } catch (IllegalArgumentException e) {
            throw new IOException("Invalid JSON artifact " + name, e);
        }
    }

    synchronized void write(String name, JsonObject value) throws IOException {
        write(name, writer -> StrictJson.GSON.toJson(value, writer));
    }

    synchronized void write(String name, JsonContent content) throws IOException {
        Objects.requireNonNull(content, "content");
        Path destination = path(name);
        Path temporary = Files.createTempFile(root, ".pending-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE,
                    StandardOpenOption.TRUNCATE_EXISTING, LinkOption.NOFOLLOW_LINKS)) {
                // Closing JsonWriter validates a complete document, but must leave the channel
                // open until force has completed. Buffering bounds memory and avoids tiny writes.
                var output = new BufferedWriter(new OutputStreamWriter(
                        new FilterOutputStream(Channels.newOutputStream(channel)) {
                            @Override public void write(byte[] bytes,int offset,int length) throws IOException {
                                out.write(bytes,offset,length);
                            }
                            @Override public void close() throws IOException { flush(); }
                        }, StandardCharsets.UTF_8), 2 * 1024);
                try (JsonWriter writer = new JsonWriter(output)) {
                    writer.setStrictness(Strictness.STRICT);
                    content.write(writer);
                }
                channel.force(true);
            }
            path(name); // Recheck directory and destination before publication.
            // No non-atomic fallback: a failed publish leaves the previous document intact.
            publisher.publish(temporary, destination);
            // POSIX supports forcing the rename metadata. Windows Java cannot open a directory
            // channel; file force + atomic replacement still protect against process interruption.
            // Do not claim full storage-device/power-loss recovery on every filesystem.
            try {
                directorySync.force(root);
            } catch (UnsupportedOperationException unsupported) {
                // Unsupported directory synchronization is not an ordinary IO failure.
                // All IOException failures still propagate to prevent further world writes.
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** Remove only records covered by an already durably published checkpoint. */
    synchronized void delete(String name) throws IOException {
        Files.deleteIfExists(path(name));
    }

    synchronized List<String> names() throws IOException {
        checkDirectory();
        List<String> names = new ArrayList<>();
        try (var stream = Files.list(root)) {
            for (Path entry : stream.toList()) {
                String filename = entry.getFileName().toString();
                if (!filename.endsWith(".json")) continue;
                String name = filename.substring(0, filename.length() - 5);
                try {
                    path(name);
                } catch (IllegalArgumentException e) {
                    throw new IOException("Invalid artifact filename: " + filename, e);
                }
                names.add(name);
            }
        }
        names.sort(Comparator.naturalOrder());
        return List.copyOf(names);
    }
}
