package dev.openallay.builder;

import dev.openallay.api.extension.ExtensionEvidence;

interface SessionInvocation {
    boolean cancelled();
    boolean completedSuccessfully();
    void evidence(ExtensionEvidence evidence);
    String loader();
    String gameVersion();
}
