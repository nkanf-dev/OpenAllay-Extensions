package dev.openallay.builder;

import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Section palette proofs never survive an owner action; the cursor holds only coordinates. */
final class SparseRegion {
    record Cell(int x,int y,int z,String state) {}
    record Slice(List<Cell> cells,long covered,long voxelReads,long paletteProven) {
        Slice { cells=List.copyOf(cells); }
    }
    static final class Cursor {
        private final BuilderBounds bounds;
        private final long width,depth,count;
        private final int minSectionX,minSectionY,minSectionZ;
        private long section,offset;
        Cursor(BuilderBounds bounds) {
            this.bounds=bounds;
            bounds.volume();
            minSectionX=Math.floorDiv(bounds.minX(),16);minSectionY=Math.floorDiv(bounds.minY(),16);minSectionZ=Math.floorDiv(bounds.minZ(),16);
            width=(long)Math.floorDiv(bounds.maxX(),16)-minSectionX+1;
            depth=(long)Math.floorDiv(bounds.maxZ(),16)-minSectionZ+1;
            long height=(long)Math.floorDiv(bounds.maxY(),16)-minSectionY+1;
            count=Math.multiplyExact(Math.multiplyExact(width,depth),height);
        }
        boolean done(){return section==count;}
        private BuilderBounds tile() {
            long x=((long)minSectionX+section%width)*16;
            long z=((long)minSectionZ+section/width%depth)*16;
            long y=((long)minSectionY+section/width/depth)*16;
            return new BuilderBounds((int)Math.max(bounds.minX(),x),(int)Math.max(bounds.minY(),y),(int)Math.max(bounds.minZ(),z),
                    (int)Math.min(bounds.maxX(),x+15),(int)Math.min(bounds.maxY(),y+15),(int)Math.min(bounds.maxZ(),z+15));
        }
        Slice capture(BuilderBackend source,int quantum) {
            List<Cell> result=new ArrayList<>();
            long covered=0,voxelReads=0,paletteProven=0,deadline=source.sliceDeadline();
            int work=0;
            while(!done() && work<quantum && (work==0 || System.nanoTime()<deadline)) {
                BuilderBounds tile=tile();
                long volume=tile.volume();
                // Re-prove on every owner action, including a partially read tile.
                // It is unsafe to retain an all-air bit across ticks or player edits.
                if(source.canonicalAir(tile)) {
                    long skipped=volume-offset;covered+=skipped;paletteProven+=skipped;
                    section++;offset=0;work++;continue;
                }
                while(offset<volume && work<quantum && (work==0 || System.nanoTime()<deadline)) {
                    BlockPos pos=tile.at(offset++);
                    String state=source.readNonAir(pos);
                    covered++;voxelReads++;work++;
                    if(state!=null)result.add(new Cell(pos.getX(),pos.getY(),pos.getZ(),state));
                }
                if(offset==volume){section++;offset=0;}
            }
            return new Slice(result,covered,voxelReads,paletteProven);
        }
    }
    private SparseRegion(){}
}
