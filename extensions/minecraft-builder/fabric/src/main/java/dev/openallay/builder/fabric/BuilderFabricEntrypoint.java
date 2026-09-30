package dev.openallay.builder.fabric;
import dev.openallay.OpenAllayBootstrap;
import dev.openallay.builder.BuilderExtension;
import net.fabricmc.api.ModInitializer;
public final class BuilderFabricEntrypoint implements ModInitializer {
    @Override public void onInitialize() { OpenAllayBootstrap.registerExtension(new BuilderExtension("fabric")); }
}
