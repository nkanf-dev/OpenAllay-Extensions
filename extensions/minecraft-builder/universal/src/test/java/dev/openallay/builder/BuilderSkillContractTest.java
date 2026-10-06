package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import com.google.gson.JsonObject;
import dev.openallay.api.extension.ExtensionContribution;
import dev.openallay.api.extension.JavascriptHostBinding;
import dev.openallay.api.extension.JavascriptHostMethod;
import dev.openallay.api.extension.SkillSource;
import dev.openallay.api.extension.SupportTarget;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Verifies packaged SDK declarations and Skill source evidence, not the core Skill parser or native host. */
final class BuilderSkillContractTest {
    private static ExtensionContribution contribution() {
        SdkFixture.Host host = new SdkFixture.Host(null);
        ExtensionContribution contribution = new BuilderExtension().contribution(host);
        assertEquals(0, host.accessRequests, "Contribution registration must not query native access");
        return contribution;
    }

    @Test
    void extensionDeclaresAllModulesAndTheProgressivelyLoadedAdvisorySkill() {
        BuilderExtension extension = new BuilderExtension();
        assertEquals("openallay:builder", extension.descriptor().id());
        assertEquals("0.4.0", extension.descriptor().version());
        assertEquals(48, extension.descriptor().support().targets().size());
        try (java.io.InputStream input = BuilderExtension.class.getClassLoader()
                .getResourceAsStream("META-INF/openallay-extension.json")) {
            assertNotNull(input);
            com.google.gson.JsonArray packaged = com.google.gson.JsonParser.parseReader(
                    new java.io.InputStreamReader(input, java.nio.charset.StandardCharsets.UTF_8))
                    .getAsJsonObject().getAsJsonObject("support").getAsJsonArray("targets");
            java.util.List<String> packagedPairs = new java.util.ArrayList<String>();
            for (com.google.gson.JsonElement item : packaged) {
                JsonObject target = item.getAsJsonObject();
                packagedPairs.add(target.get("loader").getAsString() + ":" + target.get("minecraftVersionRange").getAsString());
            }
            assertEquals(extension.descriptor().support().targets().stream()
                    .map(target -> target.loader() + ":" + target.minecraftVersionRange())
                    .collect(Collectors.toList()), packagedPairs);
        } catch (java.io.IOException failure) {
            throw new AssertionError(failure);
        }
        assertEquals(set("1.18.2", "1.19.2", "1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3"),
                extension.descriptor().support().targets().stream().map(SupportTarget::minecraftVersionRange).collect(Collectors.toSet()));
        assertEquals(set("fabric", "forge", "neoforge"), extension.descriptor().support().targets().stream()
                .map(SupportTarget::loader).collect(Collectors.toSet()));
        for (SupportTarget target : extension.descriptor().support().targets()) {
            assertEquals("[0.4.0,0.5.0)", target.openAllayApiVersionRange());
        }
        assertEquals(8, extension.descriptor().support().minimumJavaVersion());
        ExtensionContribution contribution = contribution();
        assertEquals(set("openallay_builder:building", "openallay_builder:terrain", "openallay_builder:presets"),
                contribution.javascriptModules().stream().map(module -> module.id()).collect(Collectors.toSet()));
        assertEquals(1, contribution.skills().size());
        SkillSource source = contribution.skills().get(0);
        assertTrue(source.files().keySet().stream().noneMatch(path -> path.contains("/scripts/") || path.endsWith(".js")));
        String document = source.files().get(source.entryPath());
        assertEquals("minecraft-builder", scalar(document, "name"));
        assertEquals(extension.descriptor().version(), scalar(document, "openallay/version"));
        assertFalse(Pattern.compile("(?m)^\\s*openallay/requires-capabilities:").matcher(document).find());
        assertTrue(extension.descriptor().requirements().isEmpty());
        assertEquals(Arrays.asList(BuilderBindings.MODULE), contribution.hostBindings().stream()
                .map(binding -> binding.id()).collect(Collectors.toList()));
        assertEquals("openallay:builder", scalar(document, "openallay/requires-extensions"));
        assertFalse(Pattern.compile("(?m)^required-mods:").matcher(document).find());
        assertEquals(set("openallay:run_javascript"), set(scalar(document, "allowed-tools")));
        assertEquals(set("references/api.md", "references/geometry.md", "references/decoration.md",
                "references/terrain.md", "references/presets.md", "references/templates.md", "references/execution.md"),
                references(source).keySet());
        assertTrue(document.contains("require(\"openallay_builder:building\")"));
        assertTrue(document.contains("b.finish()"));
    }

    @Test
    void hostMethodsDeclareExactDetachedJsonShapesWithoutJvmAccess() {
        JavascriptHostBinding binding = contribution().hostBindings().get(0);
        Map<String,Integer> arities = new LinkedHashMap<>();
        arities.put("open", 1); arities.put("context", 1); arities.put("read", 4);
        arities.put("readPositions", 2); arities.put("readRegion", 2);
        arities.put("scanColumns", 2); arities.put("probeColumns", 2);
        arities.put("write", 5); arities.put("writeRegion", 2); arities.put("transformState", 4);
        arities.put("updateConnections", 2); arities.put("syncPhysics", 2);
        arities.put("saveTemplate", 3); arities.put("loadTemplate", 2); arities.put("listTemplates", 1);
        arities.put("listOperations", 1); arities.put("status", 1); arities.put("finish", 1);
        arities.put("cancel", 1); arities.put("close", 1); arities.put("undo", 2);
        assertEquals(arities.keySet(), binding.methods().stream().map(method -> method.name()).collect(Collectors.toSet()));
        for (JavascriptHostMethod method : binding.methods()) {
            assertEquals(arities.get(method.name()).intValue(), method.parameters().size(), method.name());
            assertTrue(method.parameters().stream().allMatch(type -> type == dev.openallay.api.extension.JavascriptHostValueType.STRING
                    || type == dev.openallay.api.extension.JavascriptHostValueType.INTEGER), method.name());
            assertEquals(set("saveTemplate", "cancel", "close").contains(method.name())
                    ? dev.openallay.api.extension.JavascriptHostValueType.NULL
                    : dev.openallay.api.extension.JavascriptHostValueType.STRING, method.result(), method.name());
        }
        assertFalse(BuilderJsFixture.resource("building.js").contains("Java.type"));
    }

    @Test
    void packagedRegionExampleCountsEveryObservedCellInRealRhino() {
        String example = example("references/api.md", "var cells = b.read_region");
        JsonObject result = BuilderJsFixture.evaluate(String.join("\n",
                "// Inject the detached backend at the documented online entry point.",
                "building.open=function(options){return building.create(backend,options);};",
                "for(var x=-2;x<=2;x++)for(var y=64;y<=66;y++)for(var z=-2;z<=2;z++)",
                "    seed(x,y,z,{id:y===64?'minecraft:stone_bricks':'minecraft:air',properties:{}});",
                "") + "return (function(){\n" + example + "\n})();");

        assertEquals(75, result.get("observed").getAsInt());
        assertEquals(25, result.get("stoneBricks").getAsInt());
        assertEquals(set("observed", "stoneBricks"), result.keySet());
    }

    @Test
    void packagedTerrainExampleAggregatesTheCompleteScanInRealRhino() {
        String example = example("references/terrain.md", "var terrain = builder.scan_ground");
        // The detached JS backend emulates full-height scanning, not a native registry.
        JsonObject result = BuilderJsFixture.evaluateUnrestricted(String.join("\n",
                "building.open=function(options){return building.create(backend,options);};",
                "backend.read=function(x,y,z){return JSON.stringify({",
                "    id:y<=64?'minecraft:stone':'minecraft:air',properties:{}});};",
                "") + "return (function(){\n" + example + "\n})();");

        JsonObject bounds = result.getAsJsonObject("bounds");
        assertEquals(289, bounds.get("count").getAsInt());
        assertEquals(289, bounds.get("found").getAsInt());
        assertEquals(0, bounds.get("missing").getAsInt());
        assertEquals(64, bounds.get("minY").getAsInt());
        assertEquals(64, bounds.get("maxY").getAsInt());
        assertEquals(289, result.getAsJsonObject("groundBlocks").get("minecraft:stone").getAsInt());
        assertEquals(set("bounds", "groundBlocks"), result.keySet());
    }

    private static Set<String> set(String... values) { return new HashSet<>(Arrays.asList(values)); }

    /** Reads only the current shipped scalar front matter; this is not a production YAML parser. */
    private static String scalar(String document, String name) {
        int end = document.indexOf("\n---", 4);
        assertTrue(document.startsWith("---\n") && end >= 0, "Missing Skill front matter");
        Matcher scalar = Pattern.compile("(?m)^\\s*" + Pattern.quote(name) + ":\\s*(.*?)\\s*$")
                .matcher(document.substring(4, end));
        assertTrue(scalar.find(), "Missing Skill scalar: " + name);
        String value = scalar.group(1);
        if (value.startsWith("\"") && value.endsWith("\"")) value = value.substring(1, value.length() - 1);
        return value;
    }

    private static Map<String,String> references(SkillSource source) {
        String root = source.entryPath().substring(0, source.entryPath().lastIndexOf('/') + 1);
        Map<String,String> references = new LinkedHashMap<>();
        for (Map.Entry<String,String> file : source.files().entrySet()) {
            if (!file.getKey().equals(source.entryPath())) {
                assertTrue(file.getKey().startsWith(root), "Skill reference must stay under its directory");
                references.put(file.getKey().substring(root.length()), file.getValue());
            }
        }
        return references;
    }

    private static String example(String reference, String marker) {
        SkillSource source = contribution().skills().get(0);
        String document = references(source).get(reference);
        Matcher examples = Pattern.compile("```(?:javascript|js)\\R(.*?)\\R```", Pattern.DOTALL).matcher(document);
        while (examples.find()) {
            if (examples.group(1).contains(marker)) return examples.group(1);
        }
        fail("Missing packaged JavaScript example: " + marker);
        return "";
    }
}
