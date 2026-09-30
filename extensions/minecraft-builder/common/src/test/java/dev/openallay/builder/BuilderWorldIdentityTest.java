package dev.openallay.builder;
import static org.junit.jupiter.api.Assertions.*;
import com.mojang.serialization.JsonOps;
import org.junit.jupiter.api.Test;
class BuilderWorldIdentityTest {
    @org.junit.jupiter.api.io.TempDir java.nio.file.Path directory;
    @org.junit.jupiter.api.BeforeAll static void boot() { net.minecraft.SharedConstants.tryDetectVersion(); net.minecraft.server.Bootstrap.bootStrap(); }
    @Test void nativeSavedDataStorageReloadPreservesIdentity() {
        String original;
        try(var storage=new net.minecraft.world.level.storage.SavedDataStorage(directory,
                net.minecraft.util.datafix.DataFixers.getDataFixer(),net.minecraft.core.RegistryAccess.EMPTY)) {
            original=storage.computeIfAbsent(BuilderWorldIdentity.TYPE).id();
        }
        try(var storage=new net.minecraft.world.level.storage.SavedDataStorage(directory,
                net.minecraft.util.datafix.DataFixers.getDataFixer(),net.minecraft.core.RegistryAccess.EMPTY)) {
            assertEquals(original,storage.computeIfAbsent(BuilderWorldIdentity.TYPE).id());
        }
    }

    @Test void recreatedWorldGetsDifferentIdentityEvenAtSamePathAndSeed() {
        assertNotEquals(new BuilderWorldIdentity().id(),new BuilderWorldIdentity().id());
    }
    @Test void minecraftCodecReloadPreservesIncarnation() {
        BuilderWorldIdentity original=new BuilderWorldIdentity();
        var encoded=BuilderWorldIdentity.CODEC.encodeStart(JsonOps.INSTANCE,original).getOrThrow();
        var restored=BuilderWorldIdentity.CODEC.parse(JsonOps.INSTANCE,encoded).getOrThrow();
        assertEquals(original.id(),restored.id());
        assertTrue(original.isDirty());
    }
}
