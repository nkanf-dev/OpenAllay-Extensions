package dev.openallay.builder;

import dev.openallay.api.extension.ExtensionEvidence;
import dev.openallay.api.extension.ExtensionException;
import dev.openallay.api.extension.ExtensionHost;
import dev.openallay.api.extension.ExtensionInvocation;
import dev.openallay.api.extension.MinecraftWorldAccess;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Invocation-local domain sessions. Opening a participant never captures or queues native work. */
final class BuilderRuntime {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<Frame>();
    private BuilderRuntime() {}

    static String open(ExtensionInvocation context, String optionsJson) { return current(context).open(optionsJson); }
    static BuilderSession session(ExtensionInvocation context, String sessionId) {
        Frame frame = current(context);
        frame.bridge.checkWorker();
        BuilderSession session = frame.sessions.get(sessionId);
        if (session == null) throw new BuilderException("session_required", "Builder session is not owned by this invocation");
        return session;
    }
    private static Frame current(ExtensionInvocation context) {
        context.requireActive();
        Frame frame = CURRENT.get();
        if (frame == null || frame.context != context)
            throw new BuilderException("invocation_required", "Builder requires its active Extension invocation");
        return frame;
    }
    static AutoCloseable install(ExtensionInvocation context, ExtensionHost host) {
        context.requireActive();
        if (CURRENT.get() != null) throw new BuilderException("nested_invocation", "Builder invocation binding already exists");
        final Frame frame = new Frame(context, host);
        CURRENT.set(frame);
        return () -> {
            try { frame.close(); }
            finally { if (CURRENT.get() == frame) CURRENT.remove(); }
        };
    }
    private static final class Frame implements AutoCloseable, SessionInvocation {
        final ExtensionInvocation context;
        final ExtensionHost host;
        final OwnerThreadBridge bridge;
        final Thread worker = Thread.currentThread();
        final Map<String, BuilderSession> sessions = new LinkedHashMap<String, BuilderSession>();
        private BuilderBackend binding;
        private RuntimeException unavailable;
        private boolean closed;
        Frame(ExtensionInvocation context, ExtensionHost host) {
            this.context = context;
            this.host = java.util.Objects.requireNonNull(host, "host");
            bridge = new OwnerThreadBridge(context::requireActive,
                    () -> binding != null && binding.isOwnerThread());
            context.onCancel(bridge::close);
        }
        String open(String optionsJson) {
            bridge.checkWorker();
            if (unavailable != null) throw unavailable;
            if (binding == null) {
                try {
                    MinecraftWorldAccess access = host.minecraftWorldAccess();
                    if (access == null) throw new ExtensionException("world_access_unavailable", "This host has no Minecraft world access");
                    dev.openallay.api.extension.WorldSession world = access.open(context);
                    if (world == null) throw new ExtensionException("world_access_unavailable", "This host did not provide a Minecraft world session");
                    binding = new WorldSessionBackend(world);
                } catch (RuntimeException failure) { unavailable = failure; throw failure; }
            }
            BuilderSession session = new BuilderSession(this, binding, bridge, optionsJson);
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, session);
            return sessionId;
        }
        @Override public boolean cancelled() { return context.isCancelled(); }
        @Override public boolean completedSuccessfully() { return context.completedSuccessfully(); }
        @Override public void evidence(ExtensionEvidence evidence) { context.recordEvidence(evidence); }
        @Override public String correlationId() { return context.correlationId(); }
        @Override public String loader() { return host.environment().loader(); }
        @Override public String gameVersion() { return host.environment().minecraftVersion(); }
        @Override public void close() {
            if (closed) return;
            if (Thread.currentThread() != worker) throw new BuilderException("wrong_worker", "Builder invocation close must run on its worker");
            closed = true;
            bridge.close();
            RuntimeException failure = null;
            for (BuilderSession session : sessions.values()) {
                try { session.closeAfterInvocation(); }
                catch (RuntimeException next) { if (failure == null) failure = next; else failure.addSuppressed(next); }
            }
            sessions.clear();
            if (binding != null) {
                try { binding.close(); }
                catch (RuntimeException next) { if (failure == null) failure = next; else failure.addSuppressed(next); }
            }
            if (failure != null) throw failure;
        }
    }
}
