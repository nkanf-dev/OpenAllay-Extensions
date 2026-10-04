package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;

/** Deterministic shipped-source regressions with explicit role inputs, not live native registry evidence. */
final class BuilderMaterialPaletteContractTest {
    @Test
    void allPresetsRejectMissingRolesAndPreserveExactCustomStatesBeforeEffects() {
        JsonObject result = BuilderJsFixture.evaluateMaterialPaletteContract();
        assertEquals("material-palette", result.get("contract").getAsString());
        assertEquals(6, result.get("presets").getAsInt());
        assertEquals(47, result.get("roles").getAsInt());
        assertTrue(result.get("assertions").getAsInt() > 200, "Every role and custom-state regression must run");
    }
}
