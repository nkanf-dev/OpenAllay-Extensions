package dev.openallay.builder;

import dev.openallay.builder.storage.BlockPosition;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Section palette proofs never survive an owner action; the cursor holds only coordinates. */
final class SparseRegion {
    static final class Cell {
        private final int x;
        private final int y;
        private final int z;
        private final String state;

        Cell(int x, int y, int z, String state) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.state = state;
        }

        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }
        public String state() { return state; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Cell)) return false;
            Cell value = (Cell) other;
            return x == value.x
                    && y == value.y
                    && z == value.z
                    && Objects.equals(state, value.state);
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            result = 31 * result + Objects.hashCode(state);
            return result;
        }

        @Override public String toString() {
            return "Cell[x=" + x + ", y=" + y + ", z=" + z + ", state=" + state + "]";
        }
    }

    static final class Slice {
        private final List<Cell> cells;
        private final long covered;
        private final long voxelReads;
        private final long paletteProven;

        Slice(List<Cell> cells, long covered, long voxelReads, long paletteProven) {
            this.cells = immutableList(cells);
            this.covered = covered;
            this.voxelReads = voxelReads;
            this.paletteProven = paletteProven;
        }

        public List<Cell> cells() { return cells; }
        public long covered() { return covered; }
        public long voxelReads() { return voxelReads; }
        public long paletteProven() { return paletteProven; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Slice)) return false;
            Slice value = (Slice) other;
            return Objects.equals(cells, value.cells)
                    && covered == value.covered
                    && voxelReads == value.voxelReads
                    && paletteProven == value.paletteProven;
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(cells);
            result = 31 * result + Long.hashCode(covered);
            result = 31 * result + Long.hashCode(voxelReads);
            result = 31 * result + Long.hashCode(paletteProven);
            return result;
        }

        @Override public String toString() {
            return "Slice[cells=" + cells + ", covered=" + covered + ", voxelReads=" + voxelReads + ", paletteProven=" + paletteProven + "]";
        }
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
                    BlockPosition pos=tile.at(offset++);
                    String state=source.readNonAir(pos);
                    covered++;voxelReads++;work++;
                    if(state!=null)result.add(new Cell(pos.x(),pos.y(),pos.z(),state));
                }
                if(offset==volume){section++;offset=0;}
            }
            return new Slice(result,covered,voxelReads,paletteProven);
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        for (T value : copy) Objects.requireNonNull(value);
        return Collections.unmodifiableList(copy);
    }
    private SparseRegion(){}
}
