package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import dev.openallay.builder.storage.BlockPosition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ConnectionRepairTest {
    @TempDir Path directory;
    @Test void fallbackShapeHooksVisitEveryVoxelOnceInDeterministicSectionOrder() {
        List<BlockPosition> visited=new ArrayList<>();
        BuilderSessionTest.Backend backend=new BuilderSessionTest.Backend(directory) {
            @Override public String repairedState(BlockPosition pos){visited.add(pos);return null;}
        };
        ConnectionRepair.Cursor cursor=new ConnectionRepair.Cursor(new BuilderBounds(15,0,0,16,1,0));
        while(!cursor.done())cursor.capture(backend,1);
        assertEquals(FixtureValues.list(new BlockPosition(15,0,0),new BlockPosition(15,1,0),new BlockPosition(16,0,0),new BlockPosition(16,1,0)),visited);
        assertEquals(0,backend.notifyCount);
    }
}
