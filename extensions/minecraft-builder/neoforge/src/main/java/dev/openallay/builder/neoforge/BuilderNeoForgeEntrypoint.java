package dev.openallay.builder.neoforge;
import dev.openallay.OpenAllayBootstrap;
import dev.openallay.builder.BuilderExtension;
import net.neoforged.fml.common.Mod;
@Mod(value = "openallay_builder", dist = net.neoforged.api.distmarker.Dist.CLIENT)
public final class BuilderNeoForgeEntrypoint {
    public BuilderNeoForgeEntrypoint() { OpenAllayBootstrap.registerExtension(new BuilderExtension("neoforge")); }
}
