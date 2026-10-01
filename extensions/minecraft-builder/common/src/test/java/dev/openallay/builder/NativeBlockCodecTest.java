package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import net.minecraft.SharedConstants;
import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

final class NativeBlockCodecTest {
    @BeforeAll static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @Test void worldSurfaceBoundRequiresVanillaIdsForEveryPossibleNativeAirState() {
        assertTrue(NativeBinding.heightmapCovers(Blocks.AIR,"minecraft:air"));
        assertTrue(NativeBinding.heightmapCovers(Blocks.CAVE_AIR,"minecraft:cave_air"));
        assertTrue(NativeBinding.heightmapCovers(Blocks.VOID_AIR,"minecraft:void_air"));
        assertFalse(NativeBinding.heightmapCovers(Blocks.AIR,"custom:air"));
        assertFalse(NativeBinding.heightmapCovers(Blocks.CAVE_AIR,"custom:invisible_ground"));
        assertTrue(NativeBinding.heightmapCovers(Blocks.OAK_STAIRS,"custom:stairs"));
        assertTrue(Blocks.OAK_STAIRS.getStateDefinition().getPossibleStates().size()>1);
    }

    @Test void registryLookupDoesNotFallBackToAir() {
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockCodec.decode("{\"id\":\"minecraft:not_a_registered_block\"}"));
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockCodec.decode("{\"id\":\"NOT VALID\"}"));
        assertEquals(Blocks.STONE.defaultBlockState(), NativeBlockCodec.decode("{\"id\":\"stone\"}"));
    }

    @Test void propertyNamesAndValuesAreStrict() {
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockCodec.decode("{\"id\":\"stone\",\"properties\":{\"facing\":\"north\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockCodec.decode("{\"id\":\"oak_stairs\",\"properties\":{\"facing\":\"up\"}}"));
        assertThrows(IllegalArgumentException.class,
                () -> NativeBlockCodec.decode("{\"id\":\"oak_stairs\",\"properties\":{\"waterlogged\":false}}"));
        assertEquals(Direction.WEST, NativeBlockCodec.decode(
                "{\"id\":\"oak_stairs\",\"properties\":{\"facing\":\"west\"}}")
                .getValue(BlockStateProperties.HORIZONTAL_FACING));
    }

    @Test void jsonSchemaRejectsDuplicatesExtensionsAndTrailingData() {
        for (String json : new String[] {
                "{\"id\":\"stone\",\"id\":\"air\"}",
                "{\"id\":\"oak_stairs\",\"properties\":{\"facing\":\"west\",\"facing\":\"east\"}}",
                "{id:'stone'}", "{\"id\":\"stone\"} true", "{\"id\":\"stone\",\"typo\":\"ignored?\"}",
                "{\"id\":false}", "{\"id\":\"stone\",\"properties\":null}", "[]", "{}", "null"
        }) assertThrows(IllegalArgumentException.class, () -> NativeBlockCodec.decode(json), json);
    }

    @Test void snbtMustBeACompleteCompoundWithIntegerMetadataCoordinates() {
        assertNull(NativeBlockCodec.blockEntity("{\"id\":\"stone\"}"));
        assertNull(NativeBlockCodec.blockEntity("{\"id\":\"stone\",\"blockEntity\":null}"));
        for (String snbt : new String[] {"[]", "{", "{} trailing", "{x:1.5d}", "{z:5L}"}) {
            assertThrows(IllegalArgumentException.class, () -> NativeBlockCodec.blockEntity(state("chest", snbt)), snbt);
        }
        CompoundTag tag = NativeBlockCodec.blockEntity(state("chest", "{id:'minecraft:chest',x:1,y:2,z:3,Items:[]}"));
        assertEquals(1, tag.getIntOr("x", 0));
        assertEquals("minecraft:chest", tag.getStringOr("id", ""));
    }

    @Test void transformationsUseNativeStateSemantics() {
        String north = "{\"id\":\"oak_stairs\",\"properties\":{\"facing\":\"north\",\"shape\":\"inner_left\"}}";
        assertEquals(Direction.EAST, NativeBlockCodec.decode(NativeBlockCodec.transform(north, 90, "none"))
                .getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(Direction.WEST, NativeBlockCodec.decode(NativeBlockCodec.transform(north, -90, "none"))
                .getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertEquals(NativeBlockCodec.decode(north), NativeBlockCodec.decode(NativeBlockCodec.transform(north, 360, "none")));
        assertEquals(Direction.SOUTH, NativeBlockCodec.decode(NativeBlockCodec.transform(north, 0, "z"))
                .getValue(BlockStateProperties.HORIZONTAL_FACING));
        assertThrows(IllegalArgumentException.class, () -> NativeBlockCodec.transform(north, 45, "none"));
        assertThrows(IllegalArgumentException.class, () -> NativeBlockCodec.transform(north, 0, "guess"));
    }

    @Test void mirrorRunsBeforeRotationAndFullPropertiesAreEncoded() {
        String north = "{\"id\":\"oak_stairs\",\"properties\":{\"facing\":\"north\"}}";
        String transformed = NativeBlockCodec.transform(north, 90, "z");
        assertEquals(Direction.WEST, NativeBlockCodec.decode(transformed).getValue(BlockStateProperties.HORIZONTAL_FACING));
        JsonObject result = JsonParser.parseString(transformed).getAsJsonObject();
        assertEquals("minecraft:oak_stairs", result.get("id").getAsString());
        assertTrue(result.getAsJsonObject("properties").has("waterlogged"));
    }

    @Test void knownVanillaContainerInventoriesPreserveSnbtDuringTransforms() {
        for (String id : new String[] {"chest", "trapped_chest", "barrel", "shulker_box"}) {
            String snbt = "{id:'minecraft:" + id + "',x:1,y:2,z:3,Items:[{Slot:0b,id:'minecraft:stone',count:2}]}";
            String transformed = NativeBlockCodec.transform(state(id, snbt), 90, "x");
            assertEquals(snbt, JsonParser.parseString(transformed).getAsJsonObject().get("blockEntity").getAsString());
        }
    }

    @Test void opaqueDataRejectsNonidentityTransformsWithoutLosingOriginalSnbt() {
        String snbt = "{id:'minecraft:structure_block',posX:2,posY:0,posZ:3}";
        String original = state("structure_block", snbt);
        BuilderException failure = assertThrows(BuilderException.class,
                () -> NativeBlockCodec.transform(original, 90, "none"));
        assertEquals("unsupported_opaque_block_entity_transform", failure.code());
        assertEquals(snbt, JsonParser.parseString(NativeBlockCodec.transform(original, 0, "none"))
                .getAsJsonObject().get("blockEntity").getAsString());
        for (String opaque : new String[] {
                "{id:'minecraft:chest',unknownDirection:2}",
                "{id:'minecraft:chest',components:{'mod:orientation':2}}",
                "{id:'minecraft:barrel'}"
        }) assertThrows(BuilderException.class, () -> NativeBlockCodec.transform(state("chest", opaque), 90, "none"));
    }

    @Test void repeatedNativeStatesReuseDetachedImmutablePaletteValues() {
        var stone = Blocks.STONE.defaultBlockState();
        assertSame(NativeBlockCodec.stateJson(stone),NativeBlockCodec.stateJson(stone));
        var first = NativeBlockCodec.terrainState(Blocks.OAK_STAIRS.defaultBlockState());
        assertSame(first,NativeBlockCodec.terrainState(Blocks.OAK_STAIRS.defaultBlockState()));
        assertThrows(UnsupportedOperationException.class,() -> first.properties().put("facing","south"));
        assertNull(first.blockEntity());
        assertEquals(Blocks.STONE.defaultBlockState(),NativeBlockCodec.decode(NativeBlockCodec.stateJson(stone)));
    }

    @Test void decodedPaletteNeverCachesMutableEntityTagsOrAcceptsMalformedLaterInputs() {
        NativeBlockCodec.decode("{\"id\":\"minecraft:stone\",\"properties\":{}}");
        assertThrows(IllegalArgumentException.class,() -> NativeBlockCodec.decode("{\"id\":\"minecraft:stone\",\"properties\":{},\"id\":\"minecraft:air\"}"));
        String input = state("chest","{id:'minecraft:chest',x:1,y:2,z:3,Items:[]}");
        CompoundTag first = NativeBlockCodec.blockEntity(input);
        first.putInt("x",999);
        assertEquals(1,NativeBlockCodec.blockEntity(input).getIntOr("x",0));
    }

    @Test void canonicalAirSectionProofRejectsCaveVoidAndUnusedNoncanonicalPaletteValues() {
        var palette = new net.minecraft.world.level.chunk.PalettedContainer<>(Blocks.AIR.defaultBlockState(),
                net.minecraft.world.level.chunk.Strategy.createForBlockStates(net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY));
        var section = new net.minecraft.world.level.chunk.LevelChunkSection(palette,null);
        assertTrue(NativeBinding.canonicalAir(section));
        section.setBlockState(1,1,1,Blocks.CAVE_AIR.defaultBlockState());
        assertFalse(NativeBinding.canonicalAir(section),"Native isAir/hasOnlyAir cannot prove canonical identity");
        section.setBlockState(1,1,1,Blocks.VOID_AIR.defaultBlockState());
        assertFalse(NativeBinding.canonicalAir(section));
        section.setBlockState(1,1,1,Blocks.AIR.defaultBlockState());
        assertFalse(NativeBinding.canonicalAir(section),"An unused noncanonical palette entry only causes safe fallback");
    }

    private static String state(String id, String snbt) {
        JsonObject json = new JsonObject();
        json.addProperty("id", id);
        json.addProperty("blockEntity", snbt);
        return json.toString();
    }
}
