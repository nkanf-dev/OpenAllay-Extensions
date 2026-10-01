package dev.openallay.builder;

import com.google.gson.JsonObject;
import dev.openallay.context.CallerKind;
import dev.openallay.context.ToolInvocationContext;
import java.nio.file.Path;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.client.server.IntegratedServer;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;

/** Live handles never leave this native adapter. Capture and validation run on their owners. */
final class NativeBinding implements BuilderBackend {
    private final Minecraft client;
    private final IntegratedServer server;
    private final ClientPacketListener connection;
    private final LocalPlayer clientPlayer;
    private final Object clientLevel;
    private final UUID actor;
    private final String dimension;
    private ServerPlayer player;
    private ServerLevel level;
    private String worldId;
    private Path artifacts;
    private final SessionIdentity identity;
    private OwnerThreadBridge bridge;
    private OwnerThreadBridge.Owner clientOwner;
    private OwnerThreadBridge.Owner serverOwner;
    private Boolean terrainHeightmapSafe;
    // Owner-action-local pending/live BE section masks. No live chunk or proof is cached.
    private java.util.Map<Long,java.util.Set<Integer>> blockEntitySections;

    private NativeBinding(Minecraft client, ToolInvocationContext invocation) {
        if (!client.isSameThread()) throw new BuilderException("wrong_owner", "Capture requires the client owner thread");
        this.client = client;
        if (invocation.caller().kind() != CallerKind.PLAYER || invocation.player().isEmpty())
            throw new BuilderException("player_required", "An exact local player invocation is required");
        actor = invocation.caller().uuid();
        dimension = invocation.player().orElseThrow().dimension();
        server = client.getSingleplayerServer();
        connection = client.getConnection();
        clientPlayer = client.player;
        clientLevel = client.level;
        artifacts = client.gameDirectory.toPath().resolve("config/openallay-builder");
        if (server == null) throw new BuilderException("unsupported_topology", "Native Builder requires the active integrated server; remote servers are not an authoritative local backend");
        if (connection == null || clientPlayer == null || clientLevel == null) throw stale();
        identity = new SessionIdentity(connection, clientPlayer, clientLevel, server, actor, dimension);
        validateClient();
    }

    static NativeBinding capture(OwnerThreadBridge bridge, ToolInvocationContext invocation) {
        Minecraft client = Minecraft.getInstance();
        OwnerThreadBridge.Owner captureOwner = new OwnerThreadBridge.Owner(client, client::isSameThread, () -> {});
        NativeBinding binding = bridge.call(captureOwner, () -> new NativeBinding(client, invocation));
        binding.bridge = bridge;
        binding.clientOwner = new OwnerThreadBridge.Owner(client, client::isSameThread, binding::validateClient);
        binding.serverOwner = new OwnerThreadBridge.Owner(binding.server, binding.server::isSameThread, binding::validateServer);
        bridge.call(new OwnerThreadBridge.Owner(binding.server, binding.server::isSameThread, () -> {}), () -> {
            binding.player = binding.server.getPlayerList().getPlayer(binding.actor);
            if (binding.player == null) throw stale();
            binding.level = binding.player.level();
            binding.identity.bindServer(binding.player, binding.level);
            binding.worldId = binding.server.overworld().getDataStorage().computeIfAbsent(BuilderWorldIdentity.TYPE).id();
            binding.validateServer();
            return null;
        });
        return binding;
    }

    private void validateClient() {
        if (client.player == null || !connection.getConnection().isConnected()) throw stale();
        identity.requireClient(client.getConnection(), client.player, client.level, client.getSingleplayerServer(),
                client.player.getUUID(), client.player.level().dimension().identifier().toString());
    }

    private void validateServer() {
        if (server.isStopped() || server.isShutdown() || player == null || level == null
                || player.isRemoved() || !player.connection.isAcceptingMessages()
                || !connection.getConnection().isConnected()) throw stale();
        identity.requireServer(server.getPlayerList().getPlayer(actor), player.level(), player.getUUID(),
                player.level().dimension().identifier().toString());
    }

    @Override public <T> T call(java.util.concurrent.Callable<T> action) {
        return bridge.callAfter(clientOwner,serverOwner,() -> {
            blockEntitySections=new java.util.HashMap<>();
            try { return action.call(); }
            finally { blockEntitySections=null; }
        });
    }

    @Override public long sliceDeadline() { return System.nanoTime() + 4_000_000L; }
    @Override public long dispatches() { return bridge.dispatches(); }

    @Override public void validatePosition(BlockPos position) {
        validateServer();
        bridge.checkActive();
        if (level.isOutsideBuildHeight(position)) throw new BuilderException("invalid_bounds", "Position is outside the active dimension build height: " + position);
        if (!level.getWorldBorder().isWithinBounds(position)) throw new BuilderException("invalid_bounds", "Position is outside the world border: " + position);
        if (!level.hasChunkAt(position)) throw new BuilderException("chunk_unavailable", "Chunk is not loaded; no implicit chunk generation: " + position);
    }

    @Override public String context() {
        return call(() -> {
            JsonObject result = new JsonObject();
            result.addProperty("topology", "integrated-server");
            result.addProperty("dimension", dimension);
            result.addProperty("minY", level.getMinY());
            result.addProperty("maxY", Math.addExact(level.getMaxY(), 1));
            result.addProperty("version", SharedConstants.getCurrentVersion().name());
            result.addProperty("dataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
            JsonObject who = new JsonObject();
            who.addProperty("uuid", actor.toString());
            who.addProperty("x", player.getX()); who.addProperty("y", player.getY()); who.addProperty("z", player.getZ());
            who.addProperty("yaw", player.getYRot());
            result.add("player", who);
            return result.toString();
        });
    }

    @Override public String read(BlockPos pos) {
        validatePosition(pos);
        try { return NativeBlockCodec.read(level,pos); }
        finally { if(blockEntitySections!=null)blockEntitySections.clear(); }
    }
    @Override public String readNonAir(BlockPos pos) {
        validatePosition(pos);
        var state = level.getBlockState(pos);
        if (state == net.minecraft.world.level.block.Blocks.AIR.defaultBlockState() && level.getBlockEntity(pos) == null) return null;
        try { return NativeBlockCodec.read(level,pos); }
        finally {
            // Native/modded BE serializers may have side effects. A proof mask may
            // be reused only across read-only palette checks, not arbitrary hooks.
            if(blockEntitySections!=null)blockEntitySections.clear();
        }
    }
    @Override public boolean canonicalAir(BuilderBounds bounds) {
        BlockPos min=new BlockPos(bounds.minX(),bounds.minY(),bounds.minZ());
        BlockPos max=new BlockPos(bounds.maxX(),bounds.maxY(),bounds.maxZ());
        validatePosition(min);validatePosition(max);
        int chunkX=Math.floorDiv(bounds.minX(),16),chunkZ=Math.floorDiv(bounds.minZ(),16);
        int sectionY=Math.floorDiv(bounds.minY(),16);
        if(chunkX!=Math.floorDiv(bounds.maxX(),16) || chunkZ!=Math.floorDiv(bounds.maxZ(),16)
                || sectionY!=Math.floorDiv(bounds.maxY(),16))
            throw new IllegalArgumentException("Canonical-air proof requires one clipped section");
        // getChunkNow never requests or generates a missing chunk.
        var chunk=level.getChunkSource().getChunkNow(chunkX,chunkZ);
        if(chunk==null)throw new BuilderException("chunk_unavailable","Chunk is not loaded; no implicit generation: "+min);
        var section=chunk.getSection(chunk.getSectionIndex(bounds.minY()));
        if(!canonicalAir(section))return false;
        long key=net.minecraft.world.level.ChunkPos.pack(chunkX,chunkZ);
        java.util.Set<Integer> occupied=blockEntitySections.computeIfAbsent(key,ignored -> {
            java.util.Set<Integer> sections=new java.util.HashSet<>();
            for(BlockPos pos:chunk.getBlockEntitiesPos())sections.add(Math.floorDiv(pos.getY(),16));
            return sections;
        });
        return !occupied.contains(sectionY);
    }
    static boolean canonicalAir(net.minecraft.world.level.chunk.LevelChunkSection section) {
        // Palette may include stale unused values: those only cause a safe slow fallback.
        // hasOnlyAir/isAir would collapse cave, void and modded air and are not proofs.
        return !section.maybeHas(state -> state!=net.minecraft.world.level.block.Blocks.AIR.defaultBlockState());
    }
    @Override public dev.openallay.builder.storage.BlockSpec terrainState(BlockPos pos) {
        validatePosition(pos);
        return NativeBlockCodec.terrainState(level,pos);
    }
    @Override public int terrainTop(int x,int z,int minY,int maxY) {
        BlockPos bottom = new BlockPos(x,minY,z);
        validatePosition(bottom);
        validatePosition(new BlockPos(x,maxY-1,z));
        if (terrainHeightmapSafe == null) {
            terrainHeightmapSafe = true;
            // WORLD_SURFACE tests native isAir, while terrain.js excludes only three IDs.
            // Inspect every possible state once per binding, not every column/default state.
            for (var block : net.minecraft.core.registries.BuiltInRegistries.BLOCK) {
                String id = net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(block).toString();
                if (!heightmapCovers(block,id)) { terrainHeightmapSafe = false; break; }
            }
        }
        if (!terrainHeightmapSafe) return maxY-1;
        var chunk = level.getChunkAt(bottom); // Already validated loaded; no implicit generation.
        var type = net.minecraft.world.level.levelgen.Heightmap.Types.WORLD_SURFACE;
        // Never prime a missing map by scanning a whole chunk inside this bounded action.
        if (!chunk.hasPrimedHeightmap(type)) return maxY-1;
        return Math.min(maxY-1,chunk.getHeight(type,x & 15,z & 15));
    }
    static boolean heightmapCovers(net.minecraft.world.level.block.Block block,String id) {
        for (var state : block.getStateDefinition().getPossibleStates()) {
            if (state.isAir() && !TerrainScan.AIR.contains(id)) return false;
        }
        return true;
    }
    @Override public String preview(BlockPos pos,String state) { validatePosition(pos); return NativeBlockCodec.preview(level,pos,state); }
    @Override public WriteOutcome write(BlockPos pos,String state) {
        validatePosition(pos);
        return write(pos,state,NativeBlockCodec.read(level,pos));
    }
    @Override public WriteOutcome write(BlockPos pos,String state,String before) {
        validatePosition(pos);
        try {
            NativeBlockCodec.VerifiedWrite result = NativeBlockCodec.writeVerified(level,pos,state, () -> validatePosition(pos));
            return new WriteOutcome(result.actual(),result.changed(),null);
        } catch (RuntimeException failure) {
            // This action passed optimistic-before validation. Retain actual outcome even
            // when a native replacement hook fails or cancellation arrives mid-write.
            try {
                String actual = NativeBlockCodec.read(level,pos);
                return new WriteOutcome(actual,!dev.openallay.builder.storage.BlockSpec.fromJson(before).equals(
                        dev.openallay.builder.storage.BlockSpec.fromJson(actual)),failure);
            } catch (RuntimeException unreadable) {
                failure.addSuppressed(unreadable);
                throw failure; // Durable intent stays uncertain, never marked unapplied.
            }
        }
    }
    @Override public String transform(String state,int degrees,String mirror) { return NativeBlockCodec.transform(state,degrees,mirror); }
    @Override public String repairedState(BlockPos pos) {
        RepairOutcome result=repair(pos);return result==null?null:result.intended();
    }
    @Override public RepairOutcome repair(BlockPos pos) {
        validatePosition(pos);
        for(net.minecraft.core.Direction direction:net.minecraft.core.Direction.values()) {
            BlockPos neighbour=pos.relative(direction);
            if(!level.isOutsideBuildHeight(neighbour))validatePosition(neighbour);
        }
        NativeBlockCodec.Snapshot before=NativeBlockCodec.snapshot(level,pos);
        try {
            var current=before.state();
            var updated=net.minecraft.world.level.block.Block.updateFromNeighbourShapes(current,level,pos);
            if(updated==current)return null;
            // Keep the original pre-hook image for optimistic conflict checks. For a
            // changed state, retain the exact post-hook BE payload as the old repair
            // adapter did; an in-hook target mutation still fails the before gate.
            JsonObject state=com.google.gson.JsonParser.parseString(NativeBlockCodec.read(level,pos)).getAsJsonObject();
            state.addProperty("id",net.minecraft.core.registries.BuiltInRegistries.BLOCK.getKey(updated.getBlock()).toString());
            JsonObject properties=new JsonObject();
            updated.getValues().forEach(value -> properties.addProperty(value.property().getName(),value.valueName()));
            state.add("properties",properties);
            if(updated.getBlock()!=current.getBlock())state.remove("blockEntity");
            return new RepairOutcome(before.json(),state.toString());
        } finally {if(blockEntitySections!=null)blockEntitySections.clear();}
    }
    @Override public void notifyNeighbours(BlockPos pos) {
        validatePosition(pos);
        var block = level.getBlockState(pos).getBlock();
        level.updateNeighborsAt(pos,block);
        validatePosition(pos);
        level.updateNeighbourForOutputSignal(pos,block);
    }

    ServerLevel level() { return level; }
    @Override public String dimension() { return dimension; }
    @Override public String worldId() { return worldId; }
    @Override public Path artifacts() { return artifacts; }
    boolean isServerThread() { return server.isSameThread(); }
    private static BuilderException stale() { return new BuilderException("stale_session", "The exact player, connection, server or dimension binding is no longer active"); }
}
