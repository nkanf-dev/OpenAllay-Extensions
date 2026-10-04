package dev.openallay.builder;

import com.google.gson.JsonArray;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Executes canonical shared preset generators; root feeds these states to the real native codec. */
public final class BuilderNativePresetStateExport {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("One owned output path is required");
        JsonArray states = BuilderPresetsContractTest.distinctPresetStates();
        if (states.size() == 0) throw new AssertionError("All six presets must emit real states");
        Files.write(java.nio.file.Paths.get(args[0]), states.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("PRESET_STATES_EXPORTED count=" + states.size());
    }
}
