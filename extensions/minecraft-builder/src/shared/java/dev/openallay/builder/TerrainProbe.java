package dev.openallay.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockPosition;
import dev.openallay.builder.storage.BlockSpec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Bounded demand-tile capture. Local errors are detached and thrown only when JS demands that column. */
final class TerrainProbe {
    static final class Request {
        private final TerrainScan.Request scan;
        private final int clearance;
        private final int worldMinY;
        private final int worldMaxY;
        private final Integer fixedY;

        Request(TerrainScan.Request scan, int clearance, int worldMinY, int worldMaxY, Integer fixedY) {
            this.scan = scan;
            this.clearance = clearance;
            this.worldMinY = worldMinY;
            this.worldMaxY = worldMaxY;
            this.fixedY = fixedY;
        }

        public TerrainScan.Request scan() { return scan; }
        public int clearance() { return clearance; }
        public int worldMinY() { return worldMinY; }
        public int worldMaxY() { return worldMaxY; }
        public Integer fixedY() { return fixedY; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Request)) return false;
            Request value = (Request) other;
            return Objects.equals(scan, value.scan)
                    && clearance == value.clearance
                    && worldMinY == value.worldMinY
                    && worldMaxY == value.worldMaxY
                    && Objects.equals(fixedY, value.fixedY);
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(scan);
            result = 31 * result + clearance;
            result = 31 * result + worldMinY;
            result = 31 * result + worldMaxY;
            result = 31 * result + Objects.hashCode(fixedY);
            return result;
        }

        @Override public String toString() {
            return "Request[scan=" + scan + ", clearance=" + clearance + ", worldMinY=" + worldMinY + ", worldMaxY=" + worldMaxY + ", fixedY=" + fixedY + "]";
        }

        static Request parse(String json) {
            JsonObject value = JsonParser.parseString(json).getAsJsonObject();
            int clearance = BuilderBounds.integer(value,"clearance");
            int min = BuilderBounds.integer(value,"worldMinY"), max = BuilderBounds.integer(value,"worldMaxY");
            Integer fixed = value.has("fixedY") ? BuilderBounds.integer(value,"fixedY") : null;
            if (clearance < 0 || min >= max || fixed != null && (fixed < min || fixed >= max))
                throw new BuilderException("invalid_bounds","Probe clearance and world height must be valid");
            return new Request(TerrainScan.Request.parse(json),clearance,min,max,fixed);
        }
    }

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

        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("x",x); result.addProperty("y",y); result.addProperty("z",z);
            result.add("state",JsonParser.parseString(state));
            return result;
        }
    }

    static final class Result {
        private final int x;
        private final int z;
        private final Integer y;
        private final BlockSpec block;
        private final List<Cell> cells;
        private final BuilderException error;
        private final boolean scanned;

        Result(int x, int z, Integer y, BlockSpec block, List<Cell> cells, BuilderException error, boolean scanned) {
            this.x = x;
            this.z = z;
            this.y = y;
            this.block = block;
            this.cells = immutableList(cells);
            this.error = error;
            this.scanned = scanned;
        }

        public int x() { return x; }
        public int z() { return z; }
        public Integer y() { return y; }
        public BlockSpec block() { return block; }
        public List<Cell> cells() { return cells; }
        public BuilderException error() { return error; }
        public boolean scanned() { return scanned; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Result)) return false;
            Result value = (Result) other;
            return x == value.x
                    && z == value.z
                    && Objects.equals(y, value.y)
                    && Objects.equals(block, value.block)
                    && Objects.equals(cells, value.cells)
                    && Objects.equals(error, value.error)
                    && scanned == value.scanned;
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + x;
            result = 31 * result + z;
            result = 31 * result + Objects.hashCode(y);
            result = 31 * result + Objects.hashCode(block);
            result = 31 * result + Objects.hashCode(cells);
            result = 31 * result + Objects.hashCode(error);
            result = 31 * result + Boolean.hashCode(scanned);
            return result;
        }

        @Override public String toString() {
            return "Result[x=" + x + ", z=" + z + ", y=" + y + ", block=" + block + ", cells=" + cells + ", error=" + error + ", scanned=" + scanned + "]";
        }

        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("x",x);result.addProperty("z",z);
            if (!scanned) result.add("column",JsonNull.INSTANCE);
            else if (block != null || y == null) result.add("column",new TerrainScan.Column(x,z,y,block).json());
            else {
                JsonObject column = new JsonObject();
                column.addProperty("x",x);column.addProperty("z",z);column.addProperty("y",y);
                column.add("block",JsonNull.INSTANCE);column.add("properties",JsonNull.INSTANCE);
                result.add("column",column);
            }
            JsonArray values = new JsonArray(); cells.forEach(cell -> values.add(cell.json())); result.add("cells",values);
            if (error != null) {
                JsonObject failure = new JsonObject();failure.addProperty("code",error.code());failure.addProperty("message",error.getMessage());
                result.add("error",failure);
            }
            return result;
        }
    }

    static final class Slice {
        private final List<Result> columns;
        private final long reads;

        Slice(List<Result> columns, long reads) {
            this.columns = immutableList(columns);
            this.reads = reads;
        }

        public List<Result> columns() { return columns; }
        public long reads() { return reads; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Slice)) return false;
            Slice value = (Slice) other;
            return Objects.equals(columns, value.columns)
                    && reads == value.reads;
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + Objects.hashCode(columns);
            result = 31 * result + Long.hashCode(reads);
            return result;
        }

        @Override public String toString() {
            return "Slice[columns=" + columns + ", reads=" + reads + "]";
        }
    }

    static final class Cursor {
        final Request request;
        final long count;
        long index;
        boolean started, scanned;
        int y, head;
        Integer groundY;
        BlockSpec ground;
        final List<Cell> cells = new ArrayList<>();
        Cursor(Request request) { this.request=request;count=request.scan().columns(); }
        boolean done() { return index==count; }
        Slice capture(BuilderBackend source,int quantum) {
            List<Result> result = new ArrayList<>();
            long reads=0, deadline=source.sliceDeadline();
            int work=0;
            while (!done() && work<quantum && (work==0 || System.nanoTime()<deadline)) {
                int x=request.scan().x(index),z=request.scan().z(index);
                try {
                    if (!started) {
                        started=true; work++;
                        if (request.fixedY()!=null) { groundY=request.fixedY();scanned=true;head=groundY; }
                        else y=Math.min(request.scan().maxY()-1,source.terrainTop(x,z,request.scan().minY(),request.scan().maxY()));
                        continue;
                    }
                    if (!scanned) {
                        if (y<request.scan().minY()) { scanned=true;groundY=null; }
                        else {
                            BlockSpec state=source.terrainState(new BlockPosition(x,y,z));
                            if (state==null) throw new BuilderException("unobserved_block","Unknown block at "+x+","+y+","+z);
                            reads++;work++;
                            if (request.scan().accepts(state)) {groundY=y;ground=state;scanned=true;head=y;}
                            else if (y==request.scan().minY()) {scanned=true;groundY=null;}
                            else y--;
                            continue;
                        }
                    }
                    if (groundY==null || groundY<request.worldMinY() || (long)groundY+request.clearance()>=request.worldMaxY()
                            || (long)head>(long)groundY+request.clearance()) {
                        result.add(finish(x,z,null));work++;continue;
                    }
                    String state=source.read(new BlockPosition(x,head,z));
                    if (state==null) throw new BuilderException("unobserved_block","Unknown block at "+x+","+head+","+z);
                    cells.add(new Cell(x,head,z,state));reads++;work++;
                    if ((long)head==(long)groundY+request.clearance()) {result.add(finish(x,z,null));}
                    else head++;
                } catch (BuilderException failure) {
                    if (!local(failure)) throw failure;
                    // Never return partial headroom as a complete snapshot. A valid
                    // ground column remains available despite its deferred headroom error.
                    cells.clear();result.add(finish(x,z,failure));work++;
                }
            }
            return new Slice(result,reads);
        }
        private Result finish(int x,int z,BuilderException failure) {
            Result result=new Result(x,z,groundY,ground,cells,failure,scanned);
            index++;started=false;scanned=false;groundY=null;ground=null;cells.clear();
            return result;
        }
    }
    static boolean local(BuilderException failure) {
        switch (failure.code()) {
            case "chunk_unavailable":
            case "invalid_bounds":
            case "unobserved_block":
            case "missing_block_entity":
                return true;
            default:
                return false;
        }
    }

    private static <T> List<T> immutableList(List<T> values) {
        List<T> copy = new ArrayList<>(values);
        for (T value : copy) Objects.requireNonNull(value);
        return Collections.unmodifiableList(copy);
    }
    private TerrainProbe() {}
}
