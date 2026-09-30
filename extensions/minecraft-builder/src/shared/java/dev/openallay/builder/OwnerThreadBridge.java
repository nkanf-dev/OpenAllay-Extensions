package dev.openallay.builder;

import java.util.Set;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;

/** Only Java-owned actions reach game executors. No JavaScript callback crosses this boundary. */
final class OwnerThreadBridge implements AutoCloseable {
    record Owner(Executor executor, BooleanSupplier isOwnerThread, Runnable validate) {}
    private final Thread worker = Thread.currentThread();
    private final Runnable requireActive;
    private final BooleanSupplier anyOwnerThread;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Pending<?>> pending = ConcurrentHashMap.newKeySet();
    private record Pending<T>(CompletableFuture<T> future, AtomicBoolean claimed) {}

    OwnerThreadBridge(Runnable requireActive, BooleanSupplier anyOwnerThread) {
        this.requireActive = requireActive;
        this.anyOwnerThread = anyOwnerThread;
    }

    void checkWorker() {
        if (anyOwnerThread.getAsBoolean()) throw new BuilderException("owner_thread_wait", "Game owner threads cannot wait for Builder work");
        if (Thread.currentThread() != worker) throw new BuilderException("wrong_worker", "Builder sessions belong to the invocation worker");
        checkActive();
    }

    void checkActive() {
        if (closed.get()) throw new BuilderException("session_closed", "Builder scope is closed or cancelled");
        requireActive.run();
    }

    <T> T call(Owner owner, Callable<T> action) {
        checkWorker();
        if (owner.isOwnerThread().getAsBoolean()) throw new BuilderException("owner_thread_wait", "Cannot wait on the target owner thread");
        CompletableFuture<T> result = new CompletableFuture<>();
        Pending<T> task = new Pending<>(result, new AtomicBoolean());
        pending.add(task);
        try {
            checkActive();
            owner.executor().execute(() -> {
                if (!task.claimed().compareAndSet(false, true)) return;
                try {
                    checkActive();
                    if (!owner.isOwnerThread().getAsBoolean()) throw new BuilderException("wrong_owner", "Native action ran on the wrong thread");
                    owner.validate().run();
                    checkActive();
                    result.complete(action.call());
                } catch (Throwable failure) { result.completeExceptionally(failure); }
            });
            return result.get();
        } catch (InterruptedException interrupted) {
            close();
            // close atomically claims/cancels queued work. A started bounded owner action
            // must publish its outcome before worker journal cleanup; never discard it.
            try {
                return result.join();
            } catch (java.util.concurrent.CompletionException failed) {
                Throwable cause=failed.getCause();
                if(cause instanceof RuntimeException runtime) throw runtime;
                if(cause instanceof Error error) throw error;
                throw new BuilderException("native_failure","Native action failed",cause);
            } finally { Thread.currentThread().interrupt(); }
        } catch (ExecutionException failed) {
            Throwable cause = failed.getCause();
            if (cause instanceof RuntimeException runtime) throw runtime;
            if (cause instanceof Error error) throw error;
            throw new BuilderException("native_failure", "Native action failed", cause);
        } finally { pending.remove(task); }
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            BuilderException failure = new BuilderException("session_closed", "Builder scope is closed or cancelled");
            pending.forEach(task -> {
                if (task.claimed().compareAndSet(false, true)) task.future().completeExceptionally(failure);
            });
        }
    }
}
