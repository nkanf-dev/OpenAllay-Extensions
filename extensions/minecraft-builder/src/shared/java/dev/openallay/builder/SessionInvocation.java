package dev.openallay.builder;
import dev.openallay.context.EvidenceMetadata;
interface SessionInvocation {
    boolean cancelled();
    boolean completedSuccessfully();
    void evidence(EvidenceMetadata evidence);
    String loader();
}
