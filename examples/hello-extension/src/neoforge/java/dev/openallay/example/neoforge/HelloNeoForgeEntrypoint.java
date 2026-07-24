package dev.openallay.example.neoforge;

import dev.openallay.example.HelloExtension;
import dev.openallay.neoforge.OpenAllayNeoForge;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;

@Mod("openallay_hello_extension")
public final class HelloNeoForgeEntrypoint {
    public HelloNeoForgeEntrypoint(IEventBus modBus) {
        OpenAllayNeoForge.registerExtension(new HelloExtension("neoforge"));
    }
}
