package dev.openallay.builder;

import dev.openallay.extension.JavascriptInvocationContext;
import dev.openallay.extension.JavascriptInvocationParticipant;

public record BuilderParticipant(String loader) implements JavascriptInvocationParticipant {
    @Override public String id() { return "openallay_builder:invocation"; }
    @Override public AutoCloseable open(JavascriptInvocationContext context) {
        context.requireActive();
        return BuilderRuntime.install(context, loader);
    }
}
