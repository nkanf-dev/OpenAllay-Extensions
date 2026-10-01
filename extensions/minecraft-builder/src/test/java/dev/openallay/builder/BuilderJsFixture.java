package dev.openallay.builder;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import dev.openallay.model.CancellationSignal;
import dev.openallay.script.JavascriptModuleCatalog;
import dev.openallay.script.JavascriptRuntimeLimits;
import dev.openallay.script.RhinoJavascriptRuntime;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

/** Executes the shipped sources in the real, restricted OpenAllay Rhino runtime. */
final class BuilderJsFixture {
    private BuilderJsFixture() {}

    private static final String BACKEND = """
            const cells = {};
            const writes = [];
            const regions = [];
            const connections = [];
            const templates = {};
            const transforms = [];
            const lifecycle = [];
            const lifecycleState = {completed:false,cancelled:false,closed:false};
            const fixtureContext = {minY:-64,maxY:320,version:"26.2",dataVersion:5000,
                                    player:{x:12,y:70,z:-9}};
            function copy(value) { return JSON.parse(JSON.stringify(value)); }
            function key(x,y,z) { return [x,y,z].join(","); }
            function seed(x,y,z,state) { cells[key(x,y,z)] = copy(state); }
            function reset() {
                Object.keys(cells).forEach(function(k) { delete cells[k]; });
                writes.length=0; regions.length=0; connections.length=0; transforms.length=0;
            }
            function snapshot() { return copy(cells); }
            const backend = {
                context: function() { return JSON.stringify(fixtureContext); },
                read: function(x,y,z) {
                    const value=cells[key(x,y,z)];
                    return JSON.stringify(value===undefined ? null : value);
                },
                readRegion: function(json) {
                    var b=JSON.parse(String(json)),values=[];
                    for(var y=b.minY;y<=b.maxY;y++)for(var z=b.minZ;z<=b.maxZ;z++)for(var x=b.minX;x<=b.maxX;x++)
                        values.push({x:x,y:y,z:z,state:JSON.parse(backend.read(x,y,z))});
                    return JSON.stringify(values);
                },
                scanColumns: function(json) {
                    var r=JSON.parse(String(json)),columns=[];
                    var air={'minecraft:air':true,'minecraft:cave_air':true,'minecraft:void_air':true};
                    for(var x=r.minX;x<=r.maxX;x++)for(var z=r.minZ;z<=r.maxZ;z++) {
                        var found={x:x,z:z,y:null,block:null,properties:null};
                        for(var y=r.maxY-1;y>=r.minY;y--) {
                            var s=JSON.parse(backend.read(x,y,z));
                            if(!s||s.unknown||s.loaded===false||typeof s.id!=='string')throw new Error('fixture unobserved cell');
                            var p=s.properties||{},id=s.id;
                            var liquid=id==='minecraft:water'||id==='minecraft:lava'||id==='minecraft:bubble_column'||p.waterlogged==='true'||p.waterlogged===true;
                            var plant=r.vegetation.indexOf(id)>=0||(id.indexOf('minecraft:')===0&&id.indexOf('minecraft:stripped_')!==0&&/_(log|leaves|sapling)$/.test(id)&&p.persistent!=='true'&&p.persistent!==true);
                            if(!air[id]&&(!r.groundOnly||(!liquid&&!plant&&r.ground.indexOf(id)>=0))) {
                                found={x:x,z:z,y:y,block:id,properties:copy(p)};break;
                            }
                        }
                        columns.push(found);
                    }
                    return JSON.stringify(columns);
                },
                write: function(x,y,z,json) {
                    const state=JSON.parse(String(json));
                    cells[key(x,y,z)]=copy(state);
                    writes.push({x:x,y:y,z:z,state:copy(state)});
                    return JSON.stringify({writes:1});
                },
                writeRegion: function(json) {
                    var changes=JSON.parse(String(json)),i,j,c,current,expected,a,b,keys;
                    for(i=0;i<changes.length;i++) {
                        c=changes[i];
                        if(c.expectedBefore!==undefined){
                            current=JSON.parse(backend.read(c.x,c.y,c.z));expected=c.expectedBefore;
                            a=current&&current.properties||{};b=expected.properties||{};keys=Object.keys(a);
                            if(!current||current.id!==expected.id||current.blockEntity!==expected.blockEntity||keys.length!==Object.keys(b).length)throw new Error('concurrent_edit: fixture expectedBefore mismatch');
                            for(j=0;j<keys.length;j++)if(String(a[keys[j]])!==String(b[keys[j]]))throw new Error('concurrent_edit: fixture expectedBefore mismatch');
                        }
                    }
                    regions.push(copy(changes));
                    for(i=0;i<changes.length;i++){c=changes[i];backend.write(c.x,c.y,c.z,JSON.stringify(c.state));}
                    return JSON.stringify({writes:changes.length});
                },
                transformState: function(json,rotation,mirror) {
                    const state=JSON.parse(String(json));
                    transforms.push({state:copy(state),rotation:rotation,mirror:mirror});
                    // This is deliberately a small test double, not native state rotation.
                    const directions=["north","east","south","west"];
                    function direction(name) {
                        let i=directions.indexOf(name);
                        if(i<0) return name;
                        if(mirror==="front_back" && i%2===1) i=(4-i)%4;
                        if(mirror==="left_right" && i%2===0) i=(2-i+4)%4;
                        return directions[(i+rotation/90)%4];
                    }
                    const original=state.properties||{};
                    const props=copy(original);
                    if(original.facing!==undefined) props.facing=direction(original.facing);
                    if(rotation%180!==0 && (original.axis==="x" || original.axis==="z"))
                        props.axis=original.axis==="x" ? "z" : "x";
                    directions.forEach(function(d) {
                        if(original[d]!==undefined) delete props[d];
                    });
                    directions.forEach(function(d) {
                        if(original[d]!==undefined) props[direction(d)]=original[d];
                    });
                    state.properties=props;
                    return JSON.stringify(state);
                },
                updateConnections: function(json) {
                    connections.push(JSON.parse(String(json)));
                    return JSON.stringify({updated:true});
                },
                saveTemplate: function(name,json) {
                    templates[String(name)]=String(json);
                    return JSON.stringify({name:String(name),saved:true});
                },
                loadTemplate: function(name) {
                    if(templates[String(name)]===undefined) throw new Error("template not found");
                    return templates[String(name)];
                },
                listTemplates: function() { return JSON.stringify(Object.keys(templates).sort()); },
                status: function() {
                    return JSON.stringify({writes:writes.length,completed:lifecycleState.completed,
                                           cancelled:lifecycleState.cancelled,closed:lifecycleState.closed});
                },
                finish: function() { lifecycle.push("finish");lifecycleState.completed=true;return backend.status(); },
                listOperations: function() { return JSON.stringify([{id:"fixture-operation",state:"completed"}]); },
                close: function() { lifecycle.push("close");lifecycleState.closed=true; },
                cancel: function() { lifecycle.push("cancel");lifecycleState.cancelled=true; },
                undo: function(id) { lifecycle.push("undo");return JSON.stringify({undone:true,id:id||"fixture-operation"}); }
            };
            const building=require("openallay_builder:building");
            const builder=building.create(backend,{seed:"contract-seed"});
            """;

    static JsonObject evaluate(String source) {
        return execute(source).getAsJsonObject();
    }

    /** Test-only JSON transport for the internal native command; all scan work stays Java-owned. */
    static JsonObject evaluateBatched(String source, JsonElement replies) {
        String setup = "const scanRequests=[];const scanReplies=" + replies + ";" + """
                backend.scanColumns=function(json){
                    scanRequests.push(JSON.parse(String(json)));
                    if(!scanReplies.length)throw new Error('missing detached scan reply');
                    return JSON.stringify(scanReplies.shift());
                };
                const batchedBuilder=building.create(backend,{seed:'contract-seed'});
                """;
        return evaluate(setup+source);
    }

    /** Frozen old algorithm is a test oracle only, never a runtime compatibility reader. */
    static JsonObject evaluateLegacyTerrain(String source) {
        try(InputStream input=BuilderJsFixture.class.getClassLoader().getResourceAsStream("fixtures/terrain-baseline.js")) {
            if(input==null)throw new IllegalStateException("Missing legacy terrain test oracle");
            return execute(source,new String(input.readAllBytes(),StandardCharsets.UTF_8)).getAsJsonObject();
        } catch(IOException failure){throw new IllegalStateException(failure);}
    }

    static JsonElement execute(String source) { return execute(source,null,false); }

    /** Native Builder runs only in unrestricted invocations; workload assertions are not timed benchmarks. */
    static JsonObject evaluateUnrestricted(String source) { return execute(source,null,true).getAsJsonObject(); }

    private static JsonElement execute(String source,String terrainOracle) { return execute(source,terrainOracle,false); }
    private static JsonElement execute(String source,String terrainOracle,boolean unrestricted) {
        Map<String,String> sources=new LinkedHashMap<>();
        sources.put("openallay_builder:building",resource("building.js"));
        // Optional companion modules are loaded from their shipped resources, never reimplemented.
        for (String name : new String[]{"terrain", "presets"}) {
            String text=optionalResource(name+".js");
            if (text!=null) sources.put("openallay_builder:"+name,text);
        }
        if(terrainOracle!=null)sources.put("openallay_builder:terrain",terrainOracle);
        var runtime=new RhinoJavascriptRuntime(Duration.ofSeconds(10),
                JavascriptRuntimeLimits.DEFAULT,new JavascriptModuleCatalog(sources));
        return runtime.execute(BACKEND+source,Map.of(),Map.of(),Map.of(),new CancellationSignal(),null,null,unrestricted).value();
    }

    static String resource(String name) {
        String text=optionalResource(name);
        if(text==null) throw new IllegalStateException("Missing shipped builder resource: "+name);
        return text;
    }

    private static String optionalResource(String name) {
        String path="assets/openallay_builder/"+name;
        try(InputStream stream=BuilderJsFixture.class.getClassLoader().getResourceAsStream(path)) {
            return stream==null ? null : new String(stream.readAllBytes(),StandardCharsets.UTF_8);
        } catch(IOException failure) {
            throw new IllegalStateException("Unable to read "+path,failure);
        }
    }
}
