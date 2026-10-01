package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.minecraft.SharedConstants;
import net.minecraft.core.BlockPos;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.level.LevelAccessor;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionRepairTest {
    @TempDir Path directory;
    @BeforeAll static void bootstrap(){SharedConstants.tryDetectVersion();Bootstrap.bootStrap();}

    @Test void nativePaneShapesAcrossSectionBoundaryRetainBothConnectionsAndExactUndo() throws Exception {
        Map<BlockPos,BlockState> states=new HashMap<>();
        BlockPos west=new BlockPos(15,64,0),east=new BlockPos(16,64,0);
        states.put(west,Blocks.GLASS_PANE.defaultBlockState());states.put(east,Blocks.GLASS_PANE.defaultBlockState());
        // Interface-only detached native test fixture. It never obtains a live world,
        // mutates a chunk directly or invokes an unsafe native shortcut.
        LevelAccessor level=(LevelAccessor)java.lang.reflect.Proxy.newProxyInstance(LevelAccessor.class.getClassLoader(),new Class<?>[]{LevelAccessor.class},(proxy,method,args)->{
            return switch(method.getName()) {
                case "getBlockState" -> states.getOrDefault((BlockPos)args[0],Blocks.AIR.defaultBlockState());
                case "getFluidState" -> states.getOrDefault((BlockPos)args[0],Blocks.AIR.defaultBlockState()).getFluidState();
                case "getRandom" -> net.minecraft.util.RandomSource.create(0);
                case "getMinY" -> -64;
                case "getHeight" -> 384;
                default -> throw new AssertionError("Unexpected detached native world call: "+method.getName());
            };
        });
        BuilderSessionTest.Backend backend=new BuilderSessionTest.Backend(directory) {
            @Override public String read(BlockPos pos){return NativeBlockCodec.stateJson(states.getOrDefault(pos,Blocks.AIR.defaultBlockState()));}
            @Override public String preview(BlockPos pos,String state){return NativeBlockCodec.stateJson(NativeBlockCodec.decode(state));}
            @Override public RepairOutcome repair(BlockPos pos) {
                BlockState before=states.getOrDefault(pos,Blocks.AIR.defaultBlockState());
                BlockState updated=Block.updateFromNeighbourShapes(before,level,pos);
                return updated==before?null:new RepairOutcome(NativeBlockCodec.stateJson(before),NativeBlockCodec.stateJson(updated));
            }
            @Override public WriteOutcome write(BlockPos pos,String state) {
                BlockState requested=NativeBlockCodec.decode(state),previous=states.put(pos,requested);
                writeCount++;return new WriteOutcome(read(pos),requested!=previous,null);
            }
        };
        var invocation=new BuilderSessionTest.Invocation();
        BuilderSession session=new BuilderSession(invocation,backend,new OwnerThreadBridge(()->{},()->false),"{}");
        session.updateConnections("{\"minX\":15,\"maxX\":16,\"minY\":64,\"maxY\":64,\"minZ\":0,\"maxZ\":0}");
        assertTrue(states.get(west).getValue(BlockStateProperties.EAST));
        assertTrue(states.get(east).getValue(BlockStateProperties.WEST));
        assertEquals(0,backend.notifyCount);
        session.finish();
        session.undo();
        assertFalse(states.get(west).getValue(BlockStateProperties.EAST));
        assertFalse(states.get(east).getValue(BlockStateProperties.WEST));
    }

    @Test void fallbackShapeHooksVisitEveryVoxelOnceInDeterministicSectionOrder() {
        List<BlockPos> visited=new ArrayList<>();
        BuilderSessionTest.Backend backend=new BuilderSessionTest.Backend(directory) {
            @Override public String repairedState(BlockPos pos){visited.add(pos);return null;}
        };
        ConnectionRepair.Cursor cursor=new ConnectionRepair.Cursor(new BuilderBounds(15,0,0,16,1,0));
        while(!cursor.done())cursor.capture(backend,1);
        assertEquals(List.of(new BlockPos(15,0,0),new BlockPos(15,1,0),new BlockPos(16,0,0),new BlockPos(16,1,0)),visited);
        assertEquals(0,backend.notifyCount);
    }
}
