package dev.openallay.builder;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class SessionLifecycleTest {
    @Test void catchingPartialWriteFailureCannotTurnFinishIntoSuccess() {
        String state = SessionLifecycle.failed(false, 4, true);
        assertEquals("failed-partial",state);
        assertThrows(BuilderException.class, () -> SessionLifecycle.requireCompletable(state));
        assertThrows(BuilderException.class, () -> SessionLifecycle.requireWritable(state));
    }
    @Test void laterPreparationFailureRetainsEarlierAppliedEffects() {
        String state = SessionLifecycle.failed(false, 256, true);
        assertEquals("failed-partial",state);
        assertThrows(BuilderException.class, () -> SessionLifecycle.requireCompletable(state));
    }
    @Test void cancellationWithUnreturnedMutationIsUncertainPartialNotEmpty() {
        assertEquals("cancelled-partial",SessionLifecycle.cancelled(0,true));
        assertEquals("cancelled",SessionLifecycle.cancelled(0,false));
    }
    @Test void successfulReadyAndRunningOperationsCanFinish() {
        assertDoesNotThrow(() -> SessionLifecycle.requireCompletable("ready"));
        assertDoesNotThrow(() -> SessionLifecycle.requireCompletable("running"));
        assertDoesNotThrow(() -> SessionLifecycle.requireCompletable("completed"));
    }
}
