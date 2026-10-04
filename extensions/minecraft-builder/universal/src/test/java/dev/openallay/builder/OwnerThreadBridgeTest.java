package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OwnerThreadBridgeTest {
    @Test void runsActionsOnlyOnOwnerAndReturnsDetachedValues() throws Exception {
        try (FixtureValues.TestExecutorService owner = FixtureValues.executor()) {
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
        try (FixtureValues.TestExecutorService worker = FixtureValues.executor()) {
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
        try(FixtureValues.TestExecutorService owner=FixtureValues.executor();FixtureValues.TestExecutorService worker=FixtureValues.executor()) {
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
        try(FixtureValues.TestExecutorService owner=FixtureValues.executor();FixtureValues.TestExecutorService worker=FixtureValues.executor()) {
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

    @Test void chainedGateAndTargetRunOnTheirOwnersWithoutWorkerHop() {
        AtomicBoolean onGate = new AtomicBoolean(), onTarget = new AtomicBoolean();
        java.util.List<String> order = new java.util.ArrayList<>();
        OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {},() -> false);
        OwnerThreadBridge.Owner gate = new OwnerThreadBridge.Owner(action -> {
            onGate.set(true); try { action.run(); } finally { onGate.set(false); }
        },onGate::get,() -> order.add("gate"));
        OwnerThreadBridge.Owner target = new OwnerThreadBridge.Owner(action -> {
            assertTrue(onGate.get(),"Target dispatch occurs directly within gate callback");
            onTarget.set(true); try { action.run(); } finally { onTarget.set(false); }
        },onTarget::get,() -> order.add("target"));
        assertEquals("readback",bridge.callAfter(gate,target,() -> { order.add("action"); return "readback"; }));
        assertEquals(FixtureValues.list("gate","target","action"),order);
        assertEquals(2,bridge.dispatches());
    }

    @Test void cancellationBeforeQueuedGatePreventsTargetDispatch() throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        AtomicReference<Runnable> gateAction = new AtomicReference<>();
        AtomicReference<OwnerThreadBridge> reference = new AtomicReference<>();
        AtomicBoolean gateOwner = new AtomicBoolean(), targetDispatched = new AtomicBoolean();
        try (FixtureValues.TestExecutorService worker = FixtureValues.executor()) {
            Future<String> result = worker.submit(() -> {
                OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {},() -> false); reference.set(bridge);
                return bridge.callAfter(new OwnerThreadBridge.Owner(action -> { gateAction.set(action); queued.countDown(); },gateOwner::get,() -> {}),
                        new OwnerThreadBridge.Owner(action -> targetDispatched.set(true),() -> false,() -> {}),() -> "bad");
            });
            assertTrue(queued.await(2,TimeUnit.SECONDS));
            reference.get().close();
            assertThrows(java.util.concurrent.ExecutionException.class,() -> result.get(2,TimeUnit.SECONDS));
            gateOwner.set(true); gateAction.get().run(); gateOwner.set(false);
            assertFalse(targetDispatched.get());
            assertEquals(1,reference.get().dispatches());
        }
    }

    @Test void cancellationAfterGateBeforeQueuedTargetNeverStartsMutation() throws Exception {
        CountDownLatch queued = new CountDownLatch(1);
        AtomicReference<Runnable> targetAction = new AtomicReference<>();
        AtomicReference<OwnerThreadBridge> reference = new AtomicReference<>();
        AtomicBoolean gateOwner = new AtomicBoolean(), targetOwner = new AtomicBoolean(), wrote = new AtomicBoolean();
        try (FixtureValues.TestExecutorService worker = FixtureValues.executor()) {
            Future<String> result = worker.submit(() -> {
                OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {},() -> false); reference.set(bridge);
                OwnerThreadBridge.Owner gate = new OwnerThreadBridge.Owner(action -> {
                    gateOwner.set(true); try { action.run(); } finally { gateOwner.set(false); }
                },gateOwner::get,() -> {});
                return bridge.callAfter(gate,new OwnerThreadBridge.Owner(action -> {targetAction.set(action);queued.countDown();},targetOwner::get,() -> {}),
                        () -> {wrote.set(true);return "bad";});
            });
            assertTrue(queued.await(2,TimeUnit.SECONDS));
            reference.get().close();
            assertThrows(java.util.concurrent.ExecutionException.class,() -> result.get(2,TimeUnit.SECONDS));
            targetOwner.set(true); targetAction.get().run(); targetOwner.set(false);
            assertFalse(wrote.get());
            assertEquals(2,reference.get().dispatches());
        }
    }

    @Test void chainedTargetInterruptWaitsForStartedReadback() throws Exception {
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicReference<Thread> gateThread = new AtomicReference<>(), targetThread = new AtomicReference<>(), workerThread = new AtomicReference<>();
        AtomicBoolean interruptRestored = new AtomicBoolean();
        try (FixtureValues.TestExecutorService gate = FixtureValues.executor(); FixtureValues.TestExecutorService target = FixtureValues.executor(); FixtureValues.TestExecutorService worker = FixtureValues.executor()) {
            gate.submit(() -> gateThread.set(Thread.currentThread())).get();
            target.submit(() -> targetThread.set(Thread.currentThread())).get();
            Future<String> result = worker.submit(() -> {
                workerThread.set(Thread.currentThread());
                OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {},() -> Thread.currentThread()==gateThread.get() || Thread.currentThread()==targetThread.get());
                String value = bridge.callAfter(new OwnerThreadBridge.Owner(gate,() -> Thread.currentThread()==gateThread.get(),() -> {}),
                        new OwnerThreadBridge.Owner(target,() -> Thread.currentThread()==targetThread.get(),() -> {}),() -> {
                            entered.countDown();
                            if(!release.await(2,TimeUnit.SECONDS)) throw new AssertionError("test release timeout");
                            return "actual-applied-image";
                        });
                interruptRestored.set(Thread.currentThread().isInterrupted());
                return value;
            });
            assertTrue(entered.await(2,TimeUnit.SECONDS));
            workerThread.get().interrupt();
            assertFalse(result.isDone()); release.countDown();
            assertEquals("actual-applied-image",result.get(2,TimeUnit.SECONDS));
            assertTrue(interruptRestored.get());
        }
    }

    @Test void wrongWorkerCannotBorrowFacade() throws Exception {
        OwnerThreadBridge bridge = new OwnerThreadBridge(() -> {}, () -> false);
        try (FixtureValues.TestExecutorService foreign = FixtureValues.executor()) {
            java.util.concurrent.ExecutionException failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> foreign.submit(bridge::checkWorker).get());
            assertEquals("wrong_worker", ((BuilderException)failure.getCause()).code());
        }
    }
}
