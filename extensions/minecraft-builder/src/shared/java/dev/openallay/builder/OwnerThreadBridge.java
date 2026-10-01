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
    private final java.util.concurrent.atomic.AtomicLong dispatches = new java.util.concurrent.atomic.AtomicLong();
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

    <T> T call(Owner owner, Callable<T> action) { return callAfter(null,owner,action); }

    /** Chain owner validation directly to target dispatch. Neither game owner waits. */
    <T> T callAfter(Owner gate, Owner owner, Callable<T> action) {
        checkWorker();
        if (owner.isOwnerThread().getAsBoolean() || gate != null && gate.isOwnerThread().getAsBoolean())
            throw new BuilderException("owner_thread_wait", "Cannot wait on a target owner thread");
        CompletableFuture<T> result = new CompletableFuture<>();
        Pending<T> task = new Pending<>(result, new AtomicBoolean());
        pending.add(task);
        Runnable execute = () -> {
            if (!task.claimed().compareAndSet(false, true)) return;
            try {
                validateOwner(owner);
                result.complete(action.call());
            } catch (Throwable failure) { result.completeExceptionally(failure); }
        };
        try {
            checkActive();
            dispatches.incrementAndGet();
            if (gate == null) owner.executor().execute(execute);
            else gate.executor().execute(() -> {
                // Claim only when the actual bounded target action starts. close() may
                // still cancel a server task queued after the client gate has run.
                if (task.claimed().get()) return;
                try {
                    validateOwner(gate);
                    dispatches.incrementAndGet();
                    owner.executor().execute(execute);
                } catch (Throwable failure) {
                    if (task.claimed().compareAndSet(false,true)) result.completeExceptionally(failure);
                }
            });
            return result.get();
        } catch (InterruptedException interrupted) {
            close();
            // Queued work is cancelled. Started bounded actions must publish their
            // outcome before worker journal cleanup; never discard that readback.
            try { return result.join(); }
            catch (java.util.concurrent.CompletionException failed) { throw propagate(failed.getCause()); }
            finally { Thread.currentThread().interrupt(); }
        } catch (ExecutionException failed) { throw propagate(failed.getCause()); }
        finally { pending.remove(task); }
    }

    private void validateOwner(Owner owner) {
        checkActive();
        if (!owner.isOwnerThread().getAsBoolean()) throw new BuilderException("wrong_owner", "Native action ran on the wrong thread");
        owner.validate().run();
        checkActive();
    }
    private static RuntimeException propagate(Throwable cause) {
        if (cause instanceof RuntimeException runtime) return runtime;
        if (cause instanceof Error error) throw error;
        return new BuilderException("native_failure", "Native action failed", cause);
    }

    long dispatches() { return dispatches.get(); }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            BuilderException failure = new BuilderException("session_closed", "Builder scope is closed or cancelled");
            pending.forEach(task -> {
                if (task.claimed().compareAndSet(false, true)) task.future().completeExceptionally(failure);
            });
        }
    }
}
