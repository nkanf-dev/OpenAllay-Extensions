package dev.openallay.example.fabric;

import dev.openallay.example.HelloExtension;
import dev.openallay.fabric.OpenAllayFabric;
import net.fabricmc.api.ModInitializer;

public final class HelloFabricEntrypoint implements ModInitializer {
    @Override
    public void onInitialize() {
        OpenAllayFabric.registerExtension(new HelloExtension("fabric"));
    }
}
