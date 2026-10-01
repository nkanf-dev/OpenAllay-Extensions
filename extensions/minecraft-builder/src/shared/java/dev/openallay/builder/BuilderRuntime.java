package dev.openallay.builder;

import dev.openallay.extension.JavascriptInvocationContext;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Invocation-local native sessions. No Java object is exposed to JavaScript. */
final class BuilderRuntime {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private BuilderRuntime() {}

    static String open(JavascriptInvocationContext context, String optionsJson) {
        return current(context).open(optionsJson);
    }

    static BuilderSession session(JavascriptInvocationContext context, String sessionId) {
        Frame frame = current(context);
        frame.bridge.checkWorker();
        BuilderSession session = frame.sessions.get(sessionId);
        if (session == null) throw new BuilderException("session_required", "Builder session is not owned by this invocation");
        return session;
    }

    private static Frame current(JavascriptInvocationContext context) {
        context.requireActive();
        Frame frame = CURRENT.get();
        if (frame == null || frame.context != context)
            throw new BuilderException("invocation_required", "Builder requires its active Extension invocation");
        return frame;
    }

    static AutoCloseable install(JavascriptInvocationContext context, String loader) {
        return install(context, loader, (bridge, invocation) -> NativeBinding.capture(bridge, invocation));
    }

    /** Detached backend seam for authority/lifetime tests. Production always captures NativeBinding. */
    static AutoCloseable install(JavascriptInvocationContext context, String loader, BackendFactory factory) {
        context.requireActive();
        if (CURRENT.get() != null) throw new BuilderException("nested_invocation", "Builder invocation binding already exists");
        Frame frame = new Frame(context, loader, factory);
        CURRENT.set(frame);
        return () -> {
            try { frame.close(); } finally { CURRENT.remove(); }
        };
    }

    @FunctionalInterface
    interface BackendFactory {
        BuilderBackend capture(OwnerThreadBridge bridge, JavascriptInvocationContext context);
    }

    private static final class Frame implements AutoCloseable, SessionInvocation {
        final JavascriptInvocationContext context;
        final String loader;
        private final OwnerThreadBridge bridge;
        private final BackendFactory factory;
        private final Map<String, BuilderSession> sessions = new LinkedHashMap<>();
        private BuilderBackend binding;
        private RuntimeException unavailable;

        private Frame(JavascriptInvocationContext context, String loader, BackendFactory factory) {
            this.context = context;
            this.loader = loader;
            this.factory = factory;
            bridge = new OwnerThreadBridge(context::requireActive,
                    () -> binding != null && binding.isOwnerThread());
            context.cancellation().onCancel(bridge::close);
        }

        private String open(String optionsJson) {
            bridge.checkWorker();
            if (unavailable != null) throw unavailable;
            // Ordinary JavaScript does not queue Minecraft work merely because Builder is installed.
            if (binding == null) {
                try { binding = factory.capture(bridge, context); }
                catch (RuntimeException failure) { unavailable = failure; throw failure; }
            }
            BuilderSession session = new BuilderSession(this, binding, bridge, optionsJson);
            String sessionId = UUID.randomUUID().toString();
            sessions.put(sessionId, session);
            return sessionId;
        }

        @Override public boolean cancelled() { return context.cancellation().isCancelled(); }
        @Override public boolean completedSuccessfully() { return context.completedSuccessfully(); }
        @Override public void evidence(dev.openallay.context.EvidenceMetadata evidence) { context.recordEvidence(evidence); }
        @Override public String loader() { return loader; }

        @Override public void close() {
            bridge.close();
            RuntimeException failure = null;
            for (BuilderSession session : sessions.values()) {
                try { session.closeAfterInvocation(); } catch (RuntimeException next) {
                    if (failure == null) failure = next; else failure.addSuppressed(next);
                }
            }
            sessions.clear();
            if (failure != null) throw failure;
        }
    }
}
