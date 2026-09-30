package dev.openallay.builder;

import dev.openallay.extension.JavascriptInvocationContext;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.Minecraft;

/** Entry point for unrestricted JavaScript. No global mutable world facade is published. */
public final class BuilderRuntime {
    private static final ThreadLocal<Frame> CURRENT = new ThreadLocal<>();
    private BuilderRuntime() {}

    public static BuilderSession open(String optionsJson) { return current().open(optionsJson); }
    public static Frame current() {
        Frame frame = CURRENT.get();
        if (frame == null) throw new BuilderException("invocation_required", "Builder requires the unrestricted active JavaScript invocation");
        frame.context.requireActive();
        return frame;
    }

    static AutoCloseable install(JavascriptInvocationContext context, String loader) {
        if (!context.invocation().unrestrictedJavascript()) return () -> {};
        if (CURRENT.get() != null) throw new BuilderException("nested_invocation", "Builder invocation binding already exists");
        Frame frame = new Frame(context, loader);
        CURRENT.set(frame);
        return () -> {
            try { frame.close(); } finally { CURRENT.remove(); }
        };
    }

    public static final class Frame implements AutoCloseable, SessionInvocation {
        final JavascriptInvocationContext context;
        final String loader;
        private final OwnerThreadBridge bridge;
        private final List<BuilderSession> sessions = new ArrayList<>();
        private NativeBinding binding;
        private RuntimeException unavailable;

        private Frame(JavascriptInvocationContext context, String loader) {
            this.context = context;
            this.loader = loader;
            bridge = new OwnerThreadBridge(context::requireActive,
                    () -> Minecraft.getInstance().isSameThread() || (binding != null && binding.isServerThread()));
            context.cancellation().onCancel(bridge::close);
            try { binding = NativeBinding.capture(bridge, context.invocation()); }
            catch (RuntimeException failure) { unavailable = failure; }
        }

        public BuilderSession open(String optionsJson) {
            bridge.checkWorker();
            if (unavailable != null) throw unavailable;
            BuilderSession session = new BuilderSession(this, binding, bridge, optionsJson);
            sessions.add(session);
            return session;
        }

        @Override public boolean cancelled() { return context.cancellation().isCancelled(); }
        @Override public boolean completedSuccessfully() { return context.completedSuccessfully(); }
        @Override public void evidence(dev.openallay.context.EvidenceMetadata evidence) { context.recordEvidence(evidence); }
        @Override public String loader() { return loader; }

        @Override public void close() {
            bridge.close();
            RuntimeException failure = null;
            for (BuilderSession session : sessions) {
                try { session.closeAfterInvocation(); } catch (RuntimeException next) {
                    if (failure == null) failure = next; else failure.addSuppressed(next);
                }
            }
            if (failure != null) throw failure;
        }
    }
}
