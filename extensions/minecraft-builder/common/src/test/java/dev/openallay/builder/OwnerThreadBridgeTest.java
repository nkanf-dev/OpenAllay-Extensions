package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnerThreadBridgeTest {
    @Test void runsActionsOnlyOnOwnerAndReturnsDetachedValues() throws Exception {
        try (ExecutorService owner = Executors.newSingleThreadExecutor()) {
            AtomicReference<Thread> thread = new AtomicReference<>();
            owner.submit(() -> thread.set(Thread.currentThread())).get();
            OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {}, () -> Thread.currentThread() == thread.get());
            OwnerThreadBridge.Owner target = new OwnerThreadBridge.Owner(owner, () -> Thread.currentThread() == thread.get(), () -> {});
            assertEquals("detached", bridge.call(target, () -> "detached"));
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class,
                    () -> owner.submit(() -> bridge.call(target, () -> "wrong")).get());
            assertEquals("owner_thread_wait", ((BuilderException)failure.getCause()).code());
        }
    }

    @Test void closedQueuedWorkNeverRuns() throws Exception {
        ArrayDeque<Runnable> queued = new ArrayDeque<>();
        CountDownLatch submitted = new CountDownLatch(1);
        AtomicReference<OwnerThreadBridge> reference = new AtomicReference<>();
        AtomicBoolean wrote = new AtomicBoolean();
        AtomicBoolean executing = new AtomicBoolean();
        try (ExecutorService worker = Executors.newSingleThreadExecutor()) {
            Future<String> result = worker.submit(() -> {
                OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {}, () -> false);
                reference.set(bridge);
                return bridge.call(new OwnerThreadBridge.Owner(action -> { synchronized(queued) { queued.add(action); } submitted.countDown(); }, executing::get, () -> {}), () -> { wrote.set(true); return "bad"; });
            });
            assertTrue(submitted.await(2,TimeUnit.SECONDS));
            reference.get().close();
            assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(2,TimeUnit.SECONDS));
            executing.set(true);
            synchronized(queued) { queued.remove().run(); }
            executing.set(false);
            assertFalse(wrote.get());
        }
    }

    @Test void staleSameUuidSessionIsRejectedOnOwnerBeforeAction() {
        AtomicBoolean replaced = new AtomicBoolean(true);
        AtomicBoolean write = new AtomicBoolean();
        OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {}, () -> false);
        AtomicBoolean executing = new AtomicBoolean();
        OwnerThreadBridge.Owner owner = new OwnerThreadBridge.Owner(action -> { executing.set(true); try { action.run(); } finally { executing.set(false); } }, executing::get, () -> {
            if (replaced.get()) throw new BuilderException("stale_session", "same UUID but different connection object");
        });
        BuilderException failure = assertThrows(BuilderException.class, () -> bridge.call(owner, () -> { write.set(true); return null; }));
        assertEquals("stale_session", failure.code()); assertFalse(write.get());
    }

    @Test void cancellationAtExecutionTimeRejectsAction() {
        AtomicBoolean active = new AtomicBoolean(true);
        AtomicBoolean executing = new AtomicBoolean();
        AtomicBoolean write = new AtomicBoolean();
        OwnerThreadBridge bridge = new OwnerThreadBridge(() -> { if (!active.get()) throw new BuilderException("cancelled", "cancelled"); }, () -> false);
        OwnerThreadBridge.Owner owner = new OwnerThreadBridge.Owner(action -> { active.set(false); executing.set(true); try { action.run(); } finally { executing.set(false); } }, executing::get, () -> {});
        assertThrows(BuilderException.class, () -> bridge.call(owner, () -> { write.set(true); return null; }));
        assertFalse(write.get());
    }

    @Test void cancellationRetainsAlreadyStartedMutationOutcome() throws Exception {
        CountDownLatch mutated=new CountDownLatch(1), release=new CountDownLatch(1);
        AtomicReference<OwnerThreadBridge> reference=new AtomicReference<>();
        AtomicReference<Thread> ownerThread=new AtomicReference<>();
        try(ExecutorService owner=Executors.newSingleThreadExecutor();ExecutorService worker=Executors.newSingleThreadExecutor()) {
            owner.submit(()->ownerThread.set(Thread.currentThread())).get();
            Future<String> result=worker.submit(()->{
                OwnerThreadBridge bridge=new OwnerThreadBridge(()->{},()->Thread.currentThread()==ownerThread.get());
                reference.set(bridge);
                return bridge.call(new OwnerThreadBridge.Owner(owner,()->Thread.currentThread()==ownerThread.get(),()->{}),()->{
                    mutated.countDown();
                    if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("test release timeout");
                    return "applied-readback";
                });
            });
            assertTrue(mutated.await(2,TimeUnit.SECONDS));
            reference.get().close();
            assertFalse(result.isDone());
            release.countDown();
            assertEquals("applied-readback",result.get(2,TimeUnit.SECONDS));
        }
    }

    @Test void actualWorkerInterruptRetainsStartedCommitReadback() throws Exception {
        CountDownLatch mutated=new CountDownLatch(1),release=new CountDownLatch(1);
        AtomicReference<Thread> workerThread=new AtomicReference<>(),ownerThread=new AtomicReference<>();
        AtomicBoolean interruptedAfterResult=new AtomicBoolean();
        try(ExecutorService owner=Executors.newSingleThreadExecutor();ExecutorService worker=Executors.newSingleThreadExecutor()) {
            owner.submit(()->ownerThread.set(Thread.currentThread())).get();
            Future<String> result=worker.submit(()->{
                workerThread.set(Thread.currentThread());
                OwnerThreadBridge bridge=new OwnerThreadBridge(()->{},()->Thread.currentThread()==ownerThread.get());
                String value=bridge.call(new OwnerThreadBridge.Owner(owner,()->Thread.currentThread()==ownerThread.get(),()->{}),()->{
                    mutated.countDown();
                    if(!release.await(2,TimeUnit.SECONDS))throw new AssertionError("test timeout");
                    return "applied-readback";
                });
                interruptedAfterResult.set(Thread.currentThread().isInterrupted());
                return value;
            });
            assertTrue(mutated.await(2,TimeUnit.SECONDS));
            workerThread.get().interrupt();
            assertFalse(result.isDone());
            release.countDown();
            assertEquals("applied-readback",result.get(2,TimeUnit.SECONDS));
            assertTrue(interruptedAfterResult.get());
        }
    }

    @Test void wrongWorkerCannotBorrowFacade() throws Exception {
        OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {}, () -> false);
        try (ExecutorService foreign = Executors.newSingleThreadExecutor()) {
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> foreign.submit(bridge::checkWorker).get());
            assertEquals("wrong_worker", ((BuilderException)failure.getCause()).code());
        }
    }
}
