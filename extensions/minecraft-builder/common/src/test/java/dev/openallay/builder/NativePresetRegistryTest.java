package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.SharedConstants;
import net.minecraft.server.Bootstrap;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** Checks actual shipped Rhino generators against the bootstrapped Minecraft 26.2 registry. */
final class NativePresetRegistryTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void everyStateEmittedByAllSixPresetsIsValidInTheNativeRegistry() {
        // This shared fixture executes house, skyscraper, cottage, windmill, farm, and
        // dock, including all four facings and custom vanilla palettes. It gathers
        // every emitted write, not only cells that survive the last write, then dedupes.
        JsonArray states = BuilderPresetsContractTest.distinctPresetStates();
        assertFalse(states.isEmpty(), "The real preset generators must emit states");
        List<Executable> checks = new ArrayList<>();
        for (JsonElement state : states) {
            String json = state.toString();
            checks.add(() -> assertDoesNotThrow(() -> NativeBlockCodec.decode(json), json));
        }
        assertAll("All six presets must emit registered blocks and native property values", checks);
        System.out.println("Native preset registry validation: " + states.size() + " distinct emitted states");
    }
}
