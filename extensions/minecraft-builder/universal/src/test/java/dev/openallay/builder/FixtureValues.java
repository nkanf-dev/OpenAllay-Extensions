package dev.openallay.builder;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Java 8 fixture conveniences; no Minecraft classes or host capabilities are emulated. */
public final class FixtureValues {
    private FixtureValues() {}

    @SafeVarargs public static <T> List<T> list(T... values) {
        return Collections.unmodifiableList(new ArrayList<T>(Arrays.asList(values)));
    }
    @SafeVarargs public static <T> Set<T> set(T... values) {
        return Collections.unmodifiableSet(new LinkedHashSet<T>(Arrays.asList(values)));
    }
    @SuppressWarnings("unchecked") public static <K, V> Map<K, V> map(Object... pairs) {
        if (pairs.length % 2 != 0) throw new IllegalArgumentException("Expected key/value pairs");
        Map<K, V> values = new LinkedHashMap<K, V>();
        for (int index = 0; index < pairs.length; index += 2)
            values.put((K) pairs[index], (V) pairs[index + 1]);
        return Collections.unmodifiableMap(values);
    }
    public static <T> T last(List<T> values) { return values.get(values.size() - 1); }
    public static String readString(Path path) throws IOException {
        return new String(Files.readAllBytes(path), StandardCharsets.UTF_8);
    }
    public static Path writeString(Path path, String value) throws IOException {
        return Files.write(path, value.getBytes(StandardCharsets.UTF_8));
    }
    public static TestExecutorService executor() { return new TestExecutorService(); }

    /** Gives fixture executors the close-and-wait behavior absent from Java 8 ExecutorService. */
    public static final class TestExecutorService extends AbstractExecutorService implements AutoCloseable {
        private final ExecutorService delegate = Executors.newSingleThreadExecutor();
        @Override public void execute(Runnable command) { delegate.execute(command); }
        @Override public void shutdown() { delegate.shutdown(); }
        @Override public List<Runnable> shutdownNow() { return delegate.shutdownNow(); }
        @Override public boolean isShutdown() { return delegate.isShutdown(); }
        @Override public boolean isTerminated() { return delegate.isTerminated(); }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
            return delegate.awaitTermination(timeout, unit);
        }
        @Override public void close() {
            shutdown();
            boolean interrupted = false;
            try {
                while (!isTerminated()) {
                    try { awaitTermination(1, TimeUnit.DAYS); }
                    catch (InterruptedException failure) { interrupted = true; shutdownNow(); }
                }
            } finally { if (interrupted) Thread.currentThread().interrupt(); }
        }
    }
}
