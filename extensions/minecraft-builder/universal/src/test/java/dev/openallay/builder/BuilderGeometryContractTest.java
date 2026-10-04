package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

/** Independent Java lattice oracles; no Node or alternate JS engine is used. */
final class BuilderGeometryContractTest {
    private static final String STONE="minecraft:stone";
    private static final String AIR="minecraft:air";
    static final class Point {
        private final int x;
        private final int y;
        private final int z;

        Point(int x, int y, int z) {
            this.x = x;
            this.y = y;
            this.z = z;
        }

        public int x() { return x; }
        public int y() { return y; }
        public int z() { return z; }

        @Override public boolean equals(Object other) {
            if (this == other) return true;
            if (!(other instanceof Point)) return false;
            Point value = (Point) other;
            return x == value.x
                    && y == value.y
                    && z == value.z;
        }

        @Override public int hashCode() {
            int result = 0;
            result = 31 * result + x;
            result = 31 * result + y;
            result = 31 * result + z;
            return result;
        }

        @Override public String toString() {
            return "Point[x=" + x + ", y=" + y + ", z=" + z + "]";
        }

        String key() { return x+","+y+","+z; }
    }

    @Test void onePublicConstructionCallSubmitsOneDetachedWriteRegionRegardlessOfOwnerQuantum() {
        JsonObject result=BuilderJsFixture.evaluate(String.join("\n",
                "var summary=builder.build_box(0,60,0,48,60,48,'stone');",
                "return {regions:regions.length,assignments:regions[0].length,writes:writes.length,summary:summary};",
                ""));
        assertEquals(1,result.get("regions").getAsInt());assertEquals(2401,result.get("assignments").getAsInt());
        assertEquals(2401,result.get("writes").getAsInt());assertEquals(2401,result.getAsJsonObject("summary").get("writes").getAsInt());
    }

    @Test
    void boxNormalizesEveryReversedAxisAndClearsOnlyItsHollowInterior() {
        for(int flip=0;flip<8;flip++) {
            int x1=(flip&1)==0?-2:2,x2=-x1;
            int y1=(flip&2)==0?4:7,y2=11-y1;
            int z1=(flip&4)==0?-3:0,z2=-3-z1;
            for(String mode:Arrays.asList("{}","{hollow:true}","{hollow:true,clearInterior:false}")) {
                JsonObject actual=run("build_box("+x1+","+y1+","+z1+","+x2+","+y2+","+z2+",'stone',"+mode+")");
                Map<Point,String> expected=new LinkedHashMap<>();
                for(int x=-2;x<=2;x++) for(int y=4;y<=7;y++) for(int z=-3;z<=0;z++) {
                    boolean border=x==-2||x==2||y==4||y==7||z==-3||z==0;
                    if(border||mode.equals("{}")) expected.put(new Point(x,y,z),STONE);
                    else if(!mode.contains("false")) expected.put(new Point(x,y,z),AIR);
                }
                assertCells(expected,actual,"box flip="+flip+" "+mode);
            }
        }
    }

    @Test
    void degenerateBoxesHaveNoInteriorOrDuplicateWrites() {
        for(int dx=0;dx<=2;dx++) for(int dy=0;dy<=2;dy++) for(int dz=0;dz<=2;dz++) {
            Map<Point,String> expected=new LinkedHashMap<>();
            for(int x=0;x<=dx;x++) for(int y=0;y<=dy;y++) for(int z=0;z<=dz;z++) {
                boolean border=x==0||x==dx||y==0||y==dy||z==0||z==dz;
                expected.put(new Point(x,y,z),border?STONE:AIR);
            }
            assertCells(expected,run("build_box(0,0,0,"+dx+","+dy+","+dz+",'stone',{hollow:true})"),"tiny box");
        }
    }

    @Test
    void wallsAreVerticalPerimeterWithDistinctCornersAndNoDuplicateWrites() {
        JsonObject actual=run("build_walls(2,7,1,-2,4,-3,'stone',{corner:'oak_log'})");
        Map<Point,String> expected=new LinkedHashMap<>();
        for(int x=-2;x<=2;x++) for(int y=4;y<=7;y++) for(int z=-3;z<=1;z++) {
            boolean edgeX=Math.abs(x)==2,edgeZ=z==-3||z==1;
            if(edgeX||edgeZ) expected.put(new Point(x,y,z),edgeX&&edgeZ?"minecraft:oak_log":STONE);
        }
        assertCells(expected,actual,"walls");
        assertEquals(4,run("build_walls(0,0,0,0,3,0,'stone')").get("count").getAsInt());
    }

    @Test
    void checkerboardUsesWorldCoordinateParityAcrossNegativeAndReversedBounds() {
        for(boolean reversed:Arrays.asList(false,true)) {
            JsonObject actual=run(reversed?"build_floor(3,5,2,-4,-3,'stone','dirt')":"build_floor(-4,5,-3,3,2,'stone','dirt')");
            Map<Point,String> expected=new LinkedHashMap<>();
            for(int x=-4;x<=3;x++) for(int z=-3;z<=2;z++)
                expected.put(new Point(x,5,z),Math.floorMod(x+z,2)==0?STONE:"minecraft:dirt");
            assertCells(expected,actual,"checkerboard");
        }
    }

    @Test
    void circlesMatchIndependentEuclideanDiskAndFourNeighborBoundaryAtTinyRadii() {
        for(int radius=0;radius<=4;radius++) for(boolean filled:Arrays.asList(false,true)) {
            Set<Point> disk=disk(8,11,-7,radius);
            Set<Point> expected=filled?disk:boundary(disk);
            assertCells(withState(expected,STONE),run("build_circle(8,11,-7,"+radius+",'stone',{filled:"+filled+"})"),"circle r="+radius+" filled="+filled);
        }
    }

    @Test
    void cylindersMatchExtrudedDisksIncludingHollowAndExplicitAir() {
        for(int radius=0;radius<=3;radius++) for(int height=1;height<=3;height++)
            for(String mode:Arrays.asList("{}","{hollow:true}","{hollow:true,clearInterior:true}")) {
                Map<Point,String> expected=new LinkedHashMap<>();
                for(int y=4;y<4+height;y++) {
                    Set<Point> slice=disk(-5,y,6,radius),shell=boundary(slice);
                    for(Point p:slice) {
                        if(mode.equals("{}")||shell.contains(p)) expected.put(p,STONE);
                        else if(mode.contains("clearInterior:true")) expected.put(p,AIR);
                    }
                }
                assertCells(expected,run("build_cylinder(-5,4,6,"+radius+","+height+",'stone',"+mode+")"),"cylinder r="+radius+" h="+height+" "+mode);
            }
    }

    @Test
    void conesHaveInclusiveBaseAndSingleCellTipEvenAtTinyRadiiAndHeights() {
        for(int radius=0;radius<=3;radius++) for(int height=1;height<=4;height++)
            for(boolean hollow:Arrays.asList(false,true)) {
                Map<Point,String> expected=new LinkedHashMap<>();
                for(int layer=0;layer<height;layer++) {
                    // Integer cross multiplication is the independent stepped cone oracle.
                    Set<Point> layerDisk=new LinkedHashSet<>();
                    for(int dx=-radius;dx<=radius;dx++) for(int dz=-radius;dz<=radius;dz++) {
                        int permitted=height==1?radius:radius*(height-layer-1)/(height-1);
                        if(Math.hypot(dx,dz)<=permitted) layerDisk.add(new Point(2+dx,8+layer,-2+dz));
                    }
                    expected.putAll(withState(hollow?boundary(layerDisk):layerDisk,STONE));
                }
                assertCells(expected,run("build_cone(2,8,-2,"+radius+","+height+",'stone',{hollow:"+hollow+"})"),"cone r="+radius+" h="+height);
            }
    }

    @Test
    void archesMatchEllipseProjectionForBothAxesReversedSpansAndThickness() {
        for(String axis:Arrays.asList("x","z")) for(int span=0;span<=5;span++) for(int thickness=1;thickness<=2;thickness++) {
            Map<Point,String> expected=new LinkedHashMap<>();
            int height=4;
            for(int u=-2;u<=-2+span;u++) {
                double distance=span==0?0:(u-(-2+span/2.0))/(span/2.0);
                int top=5+(int)Math.floor(Math.sqrt(1-distance*distance)*(height-1)+0.5);
                int bottom=top-thickness+1;
                for(int neighbor:Arrays.asList(u-1,u+1)) if(neighbor>=-2&&neighbor<=-2+span) {
                    double adjacent=span==0?0:(neighbor-(-2+span/2.0))/(span/2.0);
                    int adjacentTop=5+(int)Math.floor(Math.sqrt(1-adjacent*adjacent)*(height-1)+0.5);
                    bottom=Math.min(bottom,adjacentTop+1);
                }
                for(int y=Math.max(5,bottom);y<=top;y++)
                    expected.put(axis.equals("x")?new Point(u,y,7):new Point(7,y,u),STONE);
            }
            assertCells(expected,run("build_arch(7,5,"+(-2+span)+",-2,4,'stone',{axis:'"+axis+"',thickness:"+thickness+"})"),"arch "+axis+" span="+span);
        }
    }

    @Test
    void pitchedRoofsHandleEvenOddWidthsBothRidgeAxesAndReversedOverhang() {
        for(String axis:Arrays.asList("x","z")) for(int width=1;width<=4;width++) for(boolean reversed:Arrays.asList(false,true)) {
            int minX=-3,maxX=axis.equals("z")?minX+width-1:1;
            int minZ=5,maxZ=axis.equals("x")?minZ+width-1:8;
            String endpoints=reversed?maxX+",20,"+maxZ+","+minX+","+minZ:minX+",20,"+minZ+","+maxX+","+maxZ;
            JsonObject actual=run("build_pitched_roof("+endpoints+",'oak_stairs','oak_slab',{axis:'"+axis+"',overhang:1})");
            Map<Point,String> expected=new LinkedHashMap<>();
            int low=axis.equals("z")?minX-1:minZ-1, high=axis.equals("z")?maxX+1:maxZ+1;
            for(int x=minX-1;x<=maxX+1;x++) for(int z=minZ-1;z<=maxZ+1;z++) {
                int cross=axis.equals("z")?x:z;
                int distance=Math.min(cross-low,high-cross);
                Point p=new Point(x,20+distance,z);
                boolean ridge=2*cross==low+high;
                expected.put(p,ridge?"minecraft:oak_slab":"minecraft:oak_stairs");
                JsonObject properties=actual.getAsJsonObject("cells").getAsJsonObject(p.key()).getAsJsonObject("properties");
                if(ridge) assertEquals("bottom",properties.get("type").getAsString());
                else {
                    String facing=axis.equals("z")?(2*cross<low+high?"east":"west"):(2*cross<low+high?"south":"north");
                    assertEquals(facing,properties.get("facing").getAsString());
                    assertEquals("bottom",properties.get("half").getAsString());
                }
            }
            assertCells(expected,actual,"roof "+axis+" width="+width+" reversed="+reversed);
        }
    }

    @Test
    void aliasesAreExactlyTheElevenCompatibilityNamesAndFullNamespacesRemainUntouched() {
        Map<String,String> expected=new LinkedHashMap<>();
        expected.put("tulip_red","red_tulip");
        expected.put("tulip_orange","orange_tulip");
        expected.put("tulip_pink","pink_tulip");
        expected.put("tulip_white","white_tulip");
        expected.put("oak_plank","oak_planks");
        expected.put("spruce_plank","spruce_planks");
        expected.put("birch_plank","birch_planks");
        expected.put("dark_oak_plank","dark_oak_planks");
        expected.put("stone_brick","stone_bricks");
        expected.put("cobble","cobblestone");
        expected.put("wood","oak_planks");
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const aliases={};",
                "Object.keys(building.BLOCK_ALIASES).forEach(function(k) { aliases[k]=builder._fix_block_name(k); });",
                "builder.place_block(1,2,3,'modded:wood',{powered:true,level:2});",
                "return {aliases:aliases,modded:builder.get_block_full(1,2,3),",
                "        vanilla:builder._fix_block_name('minecraft:wood'),java:typeof Java};",
                ""));
        assertEquals(expected.size(),actual.getAsJsonObject("aliases").size());
        expected.forEach((key,value)->assertEquals("minecraft:"+value,actual.getAsJsonObject("aliases").get(key).getAsString()));
        assertEquals("modded:wood",actual.getAsJsonObject("modded").get("id").getAsString());
        assertEquals("true",actual.getAsJsonObject("modded").getAsJsonObject("properties").get("powered").getAsString());
        assertEquals("2",actual.getAsJsonObject("modded").getAsJsonObject("properties").get("level").getAsString());
        assertEquals("minecraft:wood",actual.get("vanilla").getAsString());
        assertEquals("undefined",actual.get("java").getAsString(),"The fixture must not expose Java access");
    }

    @Test
    void unobservedNullUnknownAndUnloadedReadsNeverBecomeAir() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const failures=[];",
                "[null,{unknown:true},{loaded:false}].forEach(function(value) {",
                "    seed(1,2,3,value);",
                "    try { builder.get_block_full(1,2,3); failures.push(false); }",
                "    catch(e) { failures.push(String(e).indexOf('unobserved')>=0); }",
                "});",
                "seed(1,2,3,{id:'minecraft:air',properties:{}});",
                "return {failures:failures,air:builder.get_block(1,2,3),count:writes.length};",
                ""));
        for(JsonElement failure:actual.getAsJsonArray("failures")) assertTrue(failure.getAsBoolean());
        assertEquals(AIR,actual.get("air").getAsString());
        assertEquals(0,actual.get("count").getAsInt());
    }

    @Test
    void coordinatesAndShapeSizesRejectNonIntegersNonfiniteAndDimensionOverflow() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const rejected=[];",
                "const actions=[",
                "    function(){builder.place_block(0.5,0,0,'stone');},",
                "    function(){builder.place_block(0,NaN,0,'stone');},",
                "    function(){builder.place_block(Infinity,0,0,'stone');},",
                "    function(){builder.place_block(2147483648,0,0,'stone');},",
                "    function(){builder.place_block('1',0,0,'stone');},",
                "    function(){builder.place_block(0,-65,0,'stone');},",
                "    function(){builder.place_block(0,320,0,'stone');},",
                "    function(){builder.build_circle(0,0,0,-1,'stone');},",
                "    function(){builder.build_circle(0,0,0,1.5,'stone');},",
                "    function(){builder.build_cylinder(0,0,0,1,0,'stone');},",
                "    function(){builder.build_cone(0,0,0,1,-1,'stone');},",
                "    function(){builder.build_arch(0,0,0,1,0,'stone');},",
                "    function(){builder.build_pitched_roof(0,0,0,1,1,'oak_stairs','oak_slab',{axis:'y'});},",
                "    function(){builder.place_block(0,0,0,'Bad Namespace:stone');}",
                "];",
                "actions.forEach(function(action) { try {action();rejected.push(false);}catch(e){rejected.push(true);} });",
                "builder.place_block(0,-64,0,'stone');builder.place_block(0,319,0,'stone');",
                "return {rejected:rejected,count:writes.length};",
                ""));
        assertEquals(14,actual.getAsJsonArray("rejected").size());
        for(JsonElement rejected:actual.getAsJsonArray("rejected")) assertTrue(rejected.getAsBoolean());
        assertEquals(2,actual.get("count").getAsInt());
    }

    @Test
    void doorsAndBedsUseAllFourFacingsAndOneAtomicRegionPerPair() {
        List<String> facings=Arrays.asList("north","east","south","west");
        int[][] step={{0,-1},{1,0},{0,1},{-1,0}};
        for(int direction=0;direction<facings.size();direction++) {
            String facing=facings.get(direction);
            JsonObject door=BuilderJsFixture.evaluate("builder.place_door(2,5,-3,{facing:'"+facing+"',material:'spruce',hinge:'right',open:true});return {cells:snapshot(),regions:regions};");
            assertEquals(2,door.getAsJsonObject("cells").size());
            assertEquals(1,door.getAsJsonArray("regions").size());
            for(int level=0;level<2;level++) {
                JsonObject state=door.getAsJsonObject("cells").getAsJsonObject("2,"+(5+level)+",-3");
                assertEquals("minecraft:spruce_door",state.get("id").getAsString());
                JsonObject props=state.getAsJsonObject("properties");
                assertEquals(facing,props.get("facing").getAsString());
                assertEquals(level==0?"lower":"upper",props.get("half").getAsString());
                assertEquals("right",props.get("hinge").getAsString());
                assertEquals("true",props.get("open").getAsString());
            }
            JsonObject bed=BuilderJsFixture.evaluate("builder.place_bed(2,5,-3,{facing:'"+facing+"',color:'blue'});return {cells:snapshot(),regions:regions};");
            assertEquals(2,bed.getAsJsonObject("cells").size());
            assertEquals(1,bed.getAsJsonArray("regions").size());
            for(int part=0;part<2;part++) {
                Point p=new Point(2+part*step[direction][0],5,-3+part*step[direction][1]);
                JsonObject state=bed.getAsJsonObject("cells").getAsJsonObject(p.key());
                assertEquals("minecraft:blue_bed",state.get("id").getAsString());
                assertEquals(part==0?"foot":"head",state.getAsJsonObject("properties").get("part").getAsString());
                assertEquals(facing,state.getAsJsonObject("properties").get("facing").getAsString());
                assertEquals("false",state.getAsJsonObject("properties").get("occupied").getAsString());
            }
        }
    }

    @Test
    void twoBlockDecorValidatesBothPositionsBeforeWriting() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const rejected=[];",
                "[function(){builder.place_door(0,319,0);},",
                " function(){builder.place_bed(2147483647,5,0,{facing:'east'});},",
                " function(){builder.place_door(0,5,0,{facing:'up'});},",
                " function(){builder.place_bed(0,5,0,{facing:'diagonal'});}].forEach(function(action) {",
                "    try {action();rejected.push(false);}catch(e){rejected.push(true);}",
                " });",
                "return {rejected:rejected,count:writes.length,regions:regions.length};",
                ""));
        for(JsonElement rejected:actual.getAsJsonArray("rejected")) assertTrue(rejected.getAsBoolean());
        assertEquals(0,actual.get("count").getAsInt());
        assertEquals(0,actual.get("regions").getAsInt());
    }

    @Test
    void windowsCoverAllFourWallsAtTheRequestedSpacingWithoutCorners() {
        JsonObject actual=run("place_windows(6,7,8,-2,5,-4,{spacing:3,glass:'blue_stained_glass_pane'})");
        Map<Point,String> expected=new LinkedHashMap<>();
        for(int y=5;y<=7;y++) {
            for(int x=-1;x<6;x+=3) {
                expected.put(new Point(x,y,-4),"minecraft:blue_stained_glass_pane");
                expected.put(new Point(x,y,8),"minecraft:blue_stained_glass_pane");
            }
            for(int z=-3;z<8;z+=3) {
                expected.put(new Point(-2,y,z),"minecraft:blue_stained_glass_pane");
                expected.put(new Point(6,y,z),"minecraft:blue_stained_glass_pane");
            }
        }
        assertCells(expected,actual,"windows");
    }

    @Test
    void lanternPostPlacesExactlyHeightSupportsAndAnUprightLantern() {
        for(int height=1;height<=4;height++) {
            JsonObject actual=run("place_lantern_post(-2,10,4,{height:"+height+",post:'spruce_fence',lantern:'soul_lantern'})");
            Map<Point,String> expected=new LinkedHashMap<>();
            for(int y=10;y<10+height;y++) expected.put(new Point(-2,y,4),"minecraft:spruce_fence");
            expected.put(new Point(-2,10+height,4),"minecraft:soul_lantern");
            assertCells(expected,actual,"lantern post");
            assertEquals("false",actual.getAsJsonObject("cells").getAsJsonObject("-2,"+(10+height)+",4").getAsJsonObject("properties").get("hanging").getAsString());
        }
    }

    @Test
    void treeCrownHasPersistentLeavesAndNeverReplacesTheTrunk() {
        JsonObject actual=run("place_tree(0,10,0,{height:4,radius:1,seed:'tree-seed'})");
        JsonObject cells=actual.getAsJsonObject("cells");
        int leaves=0,trunk=0;
        for(Map.Entry<String,JsonElement> entry:cells.entrySet()) {
            JsonObject block=entry.getValue().getAsJsonObject();
            String[] xyz=entry.getKey().split(",");
            int x=Integer.parseInt(xyz[0]),y=Integer.parseInt(xyz[1]),z=Integer.parseInt(xyz[2]);
            if(block.get("id").getAsString().equals("minecraft:oak_leaves")) {
                leaves++;
                assertEquals("true",block.getAsJsonObject("properties").get("persistent").getAsString());
                assertEquals("1",block.getAsJsonObject("properties").get("distance").getAsString());
                assertTrue(Math.abs(x)<=1&&Math.abs(z)<=1&&y>=12&&y<=15);
                assertFalse(x==0&&z==0&&y<14,"No leaf may occupy the trunk");
            } else {
                trunk++;
                assertEquals("minecraft:oak_log",block.get("id").getAsString());
                assertTrue(x==0&&z==0&&y>=10&&y<=13);
            }
        }
        // Three 3x3 layers minus two trunk cells plus the one-cell top is 26.
        // Only the twelve layer corners are allowed to be randomly omitted.
        for(int y=12;y<=14;y++) for(int x=-1;x<=1;x++) for(int z=-1;z<=1;z++) {
            if((x==0&&z==0&&y<14)||(Math.abs(x)==1&&Math.abs(z)==1)) continue;
            assertEquals("minecraft:oak_leaves",cells.getAsJsonObject(x+","+y+","+z).get("id").getAsString(),"noncorner crown cell");
        }
        assertEquals("minecraft:oak_leaves",cells.getAsJsonObject("0,15,0").get("id").getAsString());
        assertTrue(leaves>=14&&leaves<=26,"leaf count="+leaves);
        assertEquals(4,trunk);
        assertEquals(leaves+trunk,actual.get("count").getAsInt());
    }

    @Test
    void seededTreesAndFlowersAreRepeatableWithoutDependingOnGlobalMathRandom() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "function scene(seedValue) {",
                "    reset();",
                "    const local=building.create(backend,{seed:seedValue});",
                "    local.place_tree(0,10,0);",
                "    for(let x=5;x<25;x++) local.place_flower(x,10,0,{potted:x%2===0});",
                "    return snapshot();",
                "}",
                "const first=scene('same-seed'),second=scene('same-seed'),third=scene('different-seed');",
                "reset();",
                "builder.place_flower(0,1,0,{seed:91});builder.place_flower(1,1,0,{seed:91});",
                "return {first:first,second:second,third:third,flowers:snapshot(),",
                "        choices:building.FLOWERS,potted:building.POTTED_FLOWERS};",
                ""));
        assertEquals(actual.get("first"),actual.get("second"));
        assertNotEquals(actual.get("first"),actual.get("third"));
        assertEquals(actual.getAsJsonObject("flowers").get("0,1,0"),actual.getAsJsonObject("flowers").get("1,1,0"));
        for(int x=5;x<25;x++) {
            JsonElement id=actual.getAsJsonObject("first").getAsJsonObject(x+",10,0").get("id");
            assertTrue(actual.getAsJsonArray(x%2==0?"potted":"choices").contains(id));
        }
    }

    @Test
    void lifecycleAndCompatibilityAliasesDelegateWithoutOpeningJavaAccess() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const result={position:builder.get_player_pos(),version:builder.detect_version(),",
                "    dependencies:building.ensure_deps(),openAliases:building.open===building.open_world&&building.open===building.quick_setup,",
                "    closeAlias:builder.close===builder.save_and_close};",
                "builder.place_block(0,5,0,'stone');",
                "result.status=builder.status();result.finished=builder.finish();",
                "result.operations=builder.list_operations();result.undo=builder.undo('selected-operation');",
                "result.cancel=builder.cancel();result.close=builder.save_and_close();",
                "try {builder.place_block(0,5,0,'dirt');result.closedRejected=false;}",
                "catch(e){result.closedRejected=true;}",
                "try {building.resolve_save_path();result.offlineRejected=false;}",
                "catch(e){result.offlineRejected=true;}",
                "result.count=writes.length;result.lifecycle=lifecycle;",
                "return result;",
                ""));
        assertEquals(12,actual.getAsJsonObject("position").get("x").getAsInt());
        assertEquals("26.2",actual.get("version").getAsString());
        assertEquals("online",actual.getAsJsonObject("dependencies").get("backend").getAsString());
        assertTrue(actual.get("openAliases").getAsBoolean());
        assertTrue(actual.get("closeAlias").getAsBoolean());
        assertTrue(actual.getAsJsonObject("finished").get("completed").getAsBoolean());
        assertTrue(actual.getAsJsonObject("cancel").get("cancelled").getAsBoolean());
        assertTrue(actual.getAsJsonObject("close").get("closed").getAsBoolean());
        assertEquals(1,actual.getAsJsonArray("operations").size());
        assertEquals("selected-operation",actual.getAsJsonObject("undo").get("id").getAsString());
        assertTrue(actual.get("closedRejected").getAsBoolean());
        assertTrue(actual.get("offlineRejected").getAsBoolean());
        assertEquals(1,actual.get("count").getAsInt());
        assertEquals(Arrays.asList("finish","undo","cancel","close"),
                jsonValues(actual.getAsJsonArray("lifecycle")).stream().map(JsonElement::getAsString).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void largeShapePlansUseOneTransportWithoutImposingABuildSizeLimit() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const result=builder.build_box(0,5,0,599,5,0,'stone');",
                "return {count:writes.length,regions:regions.map(function(r){return r.length;}),result:result};",
                ""));
        assertEquals(600,actual.get("count").getAsInt());
        assertEquals(600,actual.getAsJsonObject("result").get("writes").getAsInt());
        assertEquals(Arrays.asList(600),jsonValues(actual.getAsJsonArray("regions")).stream().map(JsonElement::getAsInt).collect(java.util.stream.Collectors.toList()), "Native tests enforce the owner quantum; JS sends one complete plan");
    }

    @Test
    void failedDispatchDoesNotFlushUnsubmittedCellsOrLeaveTheNextShapeNested() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const original=backend.writeRegion;",
                "let attempts=0;",
                "backend.writeRegion=function(json) {",
                "    attempts++;",
                "    if(attempts===1) {",
                "        // A single submitted native plan may apply one owner quantum then fail.",
                "        const changes=JSON.parse(String(json));",
                "        original(JSON.stringify(changes.slice(0,256)));",
                "        throw new Error('injected_dispatch_failure');",
                "    }",
                "    return original(json);",
                "};",
                "let error='';",
                "try {builder.build_box(0,5,0,599,5,0,'stone');}catch(e){error=String(e);}",
                "const committedBefore=writes.length;",
                "const next=builder.build_floor(0,8,0,2,0,'dirt');",
                "return {error:error,before:committedBefore,count:writes.length,next:next,",
                "        regions:regions.map(function(r){return r.length;}),attempts:attempts};",
                ""));
        assertTrue(actual.get("error").getAsString().contains("injected_dispatch_failure"));
        assertEquals(256,actual.get("before").getAsInt());
        assertEquals(259,actual.get("count").getAsInt());
        assertEquals(3,actual.getAsJsonObject("next").get("writes").getAsInt());
        assertEquals(2,actual.get("attempts").getAsInt());
        assertEquals(Arrays.asList(256,3),jsonValues(actual.getAsJsonArray("regions")).stream().map(JsonElement::getAsInt).collect(java.util.stream.Collectors.toList()));
    }

    @Test
    void connectionShapeRepairAndExplicitPhysicsUseSeparateNativeBarriers() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "const events=[];",
                "const originalRegion=backend.writeRegion;",
                "backend.writeRegion=function(json){events.push('write');return originalRegion(json);};",
                "backend.updateConnections=function(json){events.push('shape');connections.push(JSON.parse(String(json)));return '{\"changed\":0}';};",
                "backend.syncPhysics=function(json){events.push('physics');physics.push(JSON.parse(String(json)));return '{\"verified\":27}';};",
                "builder.batch(function(b){",
                "    b.place_block(0,5,0,'stone');",
                "    b.update_connections(1,6,1,0,5,0);",
                "    b.place_block(1,5,0,'stone');",
                "    b.sync_physics(1,6,1,0,5,0);",
                "});",
                "return {events:events,shape:connections,physics:physics,count:writes.length};",
                ""));
        assertEquals(Arrays.asList("write","shape","write","physics"),
                jsonValues(actual.getAsJsonArray("events")).stream().map(JsonElement::getAsString).collect(java.util.stream.Collectors.toList()));
        assertEquals(actual.get("shape"),actual.get("physics"));
        assertEquals(1,actual.getAsJsonArray("shape").size());
        assertEquals(2,actual.get("count").getAsInt());
        JsonObject bounds=actual.getAsJsonArray("shape").get(0).getAsJsonObject();
        assertEquals(0,bounds.get("minX").getAsInt());
        assertEquals(6,bounds.get("maxY").getAsInt());
    }

    @Test
    void regionAndSparseReadsMatchScalarFullStatesOrderingEntitiesAndFreshness() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "for(var y=-2;y<=0;y++)for(var z=-3;z<=-2;z++)for(var x=-4;x<=-3;x++)",
                "    seed(x,y,z,{id:'minecraft:stone',properties:{}});",
                "seed(-4,-1,-3,{id:'minecraft:chest',properties:{facing:'west'},blockEntity:'{Lock:\"test\"}'});",
                "seed(-3,0,-2,{id:'minecraft:void_air',properties:{}});",
                "const scalar=[];",
                "for(var y=-2;y<=0;y++)for(var z=-3;z<=-2;z++)for(var x=-4;x<=-3;x++)",
                "    scalar.push({x:x,y:y,z:z,state:builder.get_block_full(x,y,z)});",
                "const region=builder.read_region(-3,0,-2,-4,-2,-3);",
                "const points=[{x:-3,y:0,z:-2},{x:-4,y:-1,z:-3},{x:-3,y:0,z:-2}];",
                "const sparse=builder.get_blocks(points);",
                "seed(-3,0,-2,{id:'minecraft:cave_air',properties:{}});",
                "const fresh=builder.get_blocks(points);",
                "return {scalar:scalar,region:region,sparse:sparse,fresh:fresh};",
                ""));
        assertEquals(actual.get("scalar"),actual.get("region"));
        JsonArray sparse=actual.getAsJsonArray("sparse");
        assertEquals(3,sparse.size());
        assertEquals(sparse.get(0),sparse.get(2));
        assertEquals("minecraft:void_air",sparse.get(0).getAsJsonObject().getAsJsonObject("state").get("id").getAsString());
        assertEquals("{Lock:\"test\"}",sparse.get(1).getAsJsonObject().getAsJsonObject("state").get("blockEntity").getAsString());
        assertEquals("minecraft:cave_air",actual.getAsJsonArray("fresh").get(0).getAsJsonObject().getAsJsonObject("state").get("id").getAsString());
    }

    @Test
    void bulkInspectionFlushesPendingWritesAndRejectsUnknownWithoutPartialRows() {
        JsonObject actual=BuilderJsFixture.evaluate(String.join("\n",
                "var calls={region:0,sparse:0,scalar:0};",
                "backend.read=function(){calls.scalar++;throw new Error('scalar facade forbidden');};",
                "function observed(x,y,z){return cells[key(x,y,z)]||{id:'minecraft:air',properties:{}};}",
                "backend.readRegion=function(json){calls.region++;var b=JSON.parse(String(json)),out=[];",
                "    for(var y=b.minY;y<=b.maxY;y++)for(var z=b.minZ;z<=b.maxZ;z++)for(var x=b.minX;x<=b.maxX;x++)",
                "        out.push({x:x,y:y,z:z,state:observed(x,y,z)});",
                "    return JSON.stringify(out);",
                "};",
                "backend.readPositions=function(json){calls.sparse++;var p=JSON.parse(String(json)),out=[];",
                "    for(var i=0;i<p.length;i++)out.push({x:p[i].x,y:p[i].y,z:p[i].z,state:observed(p[i].x,p[i].y,p[i].z)});",
                "    return JSON.stringify(out);",
                "};",
                "var read=builder.batch(function(b){",
                "    b.place_block(-100,5,100,'stone');",
                "    return b.get_blocks([{x:-100,y:5,z:100},{x:100,y:5,z:-100}]);",
                "});",
                "var region=builder.read_region(0,5,0,9,5,9);",
                "seed(1,5,1,{unknown:true});",
                "var rejected=[];",
                "[function(){builder.get_blocks([{x:0,y:5,z:0},{x:1,y:5,z:1}]);},",
                " function(){builder.read_region(0,5,0,1,5,1);},",
                " function(){builder.get_blocks([{x:0,y:321,z:0}]);}].forEach(function(f){",
                "    try{f();rejected.push(false);}catch(e){rejected.push(true);}",
                "});",
                "return {read:read,count:region.length,calls:calls,rejected:rejected};",
                ""));
        assertEquals("minecraft:stone",actual.getAsJsonArray("read").get(0).getAsJsonObject().getAsJsonObject("state").get("id").getAsString());
        assertEquals(100,actual.get("count").getAsInt());
        assertEquals(2,actual.getAsJsonObject("calls").get("region").getAsInt());
        assertEquals(2,actual.getAsJsonObject("calls").get("sparse").getAsInt());
        assertEquals(0,actual.getAsJsonObject("calls").get("scalar").getAsInt());
        for(JsonElement rejected:actual.getAsJsonArray("rejected"))assertTrue(rejected.getAsBoolean());
    }

    private static List<JsonElement> jsonValues(JsonArray values) {
        List<JsonElement> result=new java.util.ArrayList<>();
        for(JsonElement value:values) result.add(value);
        return result;
    }

    private static JsonObject run(String call) {
        return BuilderJsFixture.evaluate("const result=builder."+call+";return {cells:snapshot(),count:writes.length,result:result};");
    }

    private static Set<Point> disk(int cx,int y,int cz,int radius) {
        Set<Point> points=new LinkedHashSet<>();
        for(int x=cx-radius;x<=cx+radius;x++) for(int z=cz-radius;z<=cz+radius;z++)
            if(Math.hypot(x-cx,z-cz)<=radius) points.add(new Point(x,y,z));
        return points;
    }

    private static Set<Point> boundary(Set<Point> disk) {
        Set<Point> boundary=new LinkedHashSet<>();
        for(Point p:disk) {
            if(!disk.contains(new Point(p.x()+1,p.y(),p.z()))||!disk.contains(new Point(p.x()-1,p.y(),p.z()))
                    ||!disk.contains(new Point(p.x(),p.y(),p.z()+1))||!disk.contains(new Point(p.x(),p.y(),p.z()-1))) boundary.add(p);
        }
        return boundary;
    }

    private static Map<Point,String> withState(Set<Point> positions,String state) {
        Map<Point,String> result=new LinkedHashMap<>();
        positions.forEach(p->result.put(p,state));
        return result;
    }

    private static void assertCells(Map<Point,String> expected,JsonObject actual,String description) {
        JsonObject cells=actual.getAsJsonObject("cells");
        Set<String> expectedKeys=new LinkedHashSet<>();
        expected.keySet().forEach(p->expectedKeys.add(p.key()));
        assertEquals(expectedKeys,cells.keySet(),description+" positions");
        expected.forEach((point,id)->assertEquals(id,cells.getAsJsonObject(point.key()).get("id").getAsString(),description+" "+point));
        assertEquals(expected.size(),actual.get("count").getAsInt(),description+" must not repeat writes");
        assertEquals(expected.size(),actual.getAsJsonObject("result").get("writes").getAsInt(),description+" summary");
    }
}
