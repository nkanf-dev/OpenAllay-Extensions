package dev.openallay.builder;

import java.util.Objects;
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
    static final class Owner {
        private final Executor executor;
        private final BooleanSupplier isOwnerThread;
        private final Runnable validate;

        Owner(Executor executor, BooleanSupplier isOwnerThread, Runnable validate) {
            this.executor = executor;
            this.isOwnerThread = isOwnerThread;
            this.validate = validate;
        }

        public Executor executor() { return executor; }
        public BooleanSupplier isOwnerThread() { return isOwnerThread; }
        public Runnable validate() { return validate; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Owner)) return false;
            Owner value = (Owner) other;
            return Objects.equals(executor, value.executor)
                    && Objects.equals(isOwnerThread, value.isOwnerThread)
                    && Objects.equals(validate, value.validate);
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(executor);
            result = 31 * result + Objects.hashCode(isOwnerThread);
            result = 31 * result + Objects.hashCode(validate);
            return result;
        }

        @Override public String toString() {
            return "Owner[executor=" + executor + ", isOwnerThread=" + isOwnerThread + ", validate=" + validate + "]";
        }
    }
    private final Thread worker = Thread.currentThread();
    private final Runnable requireActive;
    private final java.util.concurrent.atomic.AtomicLong dispatches = new java.util.concurrent.atomic.AtomicLong();
    private final BooleanSupplier anyOwnerThread;
    private final AtomicBoolean closed = new AtomicBoolean();
    private final Set<Pending<?>> pending = ConcurrentHashMap.newKeySet();
    private static final class Pending<T> {
        private final CompletableFuture<T> future;
        private final AtomicBoolean claimed;

        Pending(CompletableFuture<T> future, AtomicBoolean claimed) {
            this.future = future;
            this.claimed = claimed;
        }

        public CompletableFuture<T> future() { return future; }
        public AtomicBoolean claimed() { return claimed; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Pending)) return false;
            Pending<?> value = (Pending<?>) other;
            return Objects.equals(future, value.future)
                    && Objects.equals(claimed, value.claimed);
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(future);
            result = 31 * result + Objects.hashCode(claimed);
            return result;
        }

        @Override public String toString() {
            return "Pending[future=" + future + ", claimed=" + claimed + "]";
        }
    }

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
        if (cause instanceof RuntimeException) return (RuntimeException) cause;
        if (cause instanceof Error) throw (Error) cause;
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
