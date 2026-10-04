package dev.openallay.builder;

import dev.openallay.api.extension.ExtensionHost;
import dev.openallay.api.extension.ExtensionInvocation;
import dev.openallay.api.extension.JavascriptInvocationParticipant;

/** Host injection is detached; native access remains lazy until Builder.open. */
public final class BuilderParticipant implements JavascriptInvocationParticipant {
    private final ExtensionHost host;
    public BuilderParticipant(ExtensionHost host) { this.host = java.util.Objects.requireNonNull(host, "host"); }
    @Override public String id() { return "openallay_builder:invocation"; }
    @Override public AutoCloseable open(ExtensionInvocation context) {
        context.requireActive();
        return BuilderRuntime.install(context, host);
    }
}
