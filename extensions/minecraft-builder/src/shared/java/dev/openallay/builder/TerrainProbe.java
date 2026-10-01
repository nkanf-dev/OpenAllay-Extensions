package dev.openallay.builder;

import com.google.gson.JsonArray;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import dev.openallay.builder.storage.BlockSpec;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.core.BlockPos;

/** Bounded demand-tile capture. Local errors are detached and thrown only when JS demands that column. */
final class TerrainProbe {
    record Request(TerrainScan.Request scan, int clearance, int worldMinY, int worldMaxY, Integer fixedY) {
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
    record Cell(int x,int y,int z,String state) {
        JsonObject json() {
            JsonObject result = new JsonObject();
            result.addProperty("x",x); result.addProperty("y",y); result.addProperty("z",z);
            result.add("state",JsonParser.parseString(state));
            return result;
        }
    }
    record Result(int x,int z,Integer y,BlockSpec block,List<Cell> cells,BuilderException error,boolean scanned) {
        Result { cells = List.copyOf(cells); }
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
    record Slice(List<Result> columns,long reads) { Slice { columns=List.copyOf(columns); } }

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
                            BlockSpec state=source.terrainState(new BlockPos(x,y,z));
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
                    String state=source.read(new BlockPos(x,head,z));
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
        return switch(failure.code()) {
            case "chunk_unavailable","invalid_bounds","unobserved_block","missing_block_entity" -> true;
            default -> false;
        };
    }
    private TerrainProbe() {}
}
