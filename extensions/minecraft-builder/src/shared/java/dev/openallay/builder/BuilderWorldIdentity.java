package dev.openallay.builder;

import com.mojang.serialization.Codec;
import java.util.UUID;
import net.minecraft.resources.Identifier;
import net.minecraft.world.level.saveddata.SavedData;
import net.minecraft.world.level.saveddata.SavedDataType;

/** Save incarnation owned and persisted by Minecraft's live SavedData subsystem. */
final class BuilderWorldIdentity extends SavedData {
    static final Codec<BuilderWorldIdentity> CODEC = com.mojang.serialization.codecs.RecordCodecBuilder.create(instance -> instance.group(
            Codec.INT.fieldOf("version").forGetter(value -> 1),
            Codec.STRING.fieldOf("uuid").forGetter(value -> value.id.toString())
    ).apply(instance,(version,id) -> {
        if(version != 1) throw new IllegalArgumentException("Unsupported Builder world identity version: "+version);
        return new BuilderWorldIdentity(UUID.fromString(id));
    }));
    static final SavedDataType<BuilderWorldIdentity> TYPE = new SavedDataType<>(
            Identifier.fromNamespaceAndPath("openallay_builder","world_identity"), BuilderWorldIdentity::new, CODEC, net.minecraft.util.datafix.DataFixTypes.SAVED_DATA_COMMAND_STORAGE);
    private final UUID id;
    BuilderWorldIdentity() { this(UUID.randomUUID()); setDirty(); }
    private BuilderWorldIdentity(UUID id) { this.id=id; }
    String id() { return id.toString(); }
}
