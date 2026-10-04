package dev.openallay.builder;
import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
class ExpectedImageTest {
    @Test void connectionRepairCannotReplaceInterveningEditWithDerivedStaleState() {
        String captured = "{\"id\":\"minecraft:oak_fence\",\"properties\":{\"north\":\"false\"}}";
        String concurrent = "{\"id\":\"minecraft:stone\",\"properties\":{}}";
        BuilderException failure = assertThrows(BuilderException.class,
                () -> ExpectedImage.requireUnchanged(captured,concurrent,"1,2,3"));
        assertEquals("concurrent_edit",failure.code());
        assertDoesNotThrow(() -> ExpectedImage.requireUnchanged(captured,captured,"1,2,3"));
    }
    @Test void undoAlsoChecksCompleteBlockEntityImage() {
        String expected = "{\"id\":\"minecraft:chest\",\"properties\":{},\"blockEntity\":\"{id:'minecraft:chest',Items:[]}\"}";
        String changed = "{\"id\":\"minecraft:chest\",\"properties\":{},\"blockEntity\":\"{id:'minecraft:chest',Items:[{Slot:0b,id:'minecraft:stone',count:1}]}\"}";
        assertThrows(BuilderException.class, () -> ExpectedImage.requireUnchanged(expected,changed,"0,0,0"));
    }
}
