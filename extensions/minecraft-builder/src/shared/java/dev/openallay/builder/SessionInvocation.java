package dev.openallay.builder;

import dev.openallay.api.extension.ExtensionEvidence;

interface SessionInvocation {
    boolean cancelled();
    boolean completedSuccessfully();
    void evidence(ExtensionEvidence evidence);
    default String correlationId() { return null; }
    String loader();
    String gameVersion();
}
