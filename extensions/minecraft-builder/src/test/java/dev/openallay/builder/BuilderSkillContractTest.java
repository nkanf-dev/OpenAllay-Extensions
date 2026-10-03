package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import dev.openallay.skill.SkillParser;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Verifies the actual packaged Skill and reviewed module contribution, not copied documentation. */
final class BuilderSkillContractTest {
    @Test
    void extensionDeclaresAllModulesAndTheProgressivelyLoadedAdvisorySkill() {
        var extension = new BuilderExtension("fabric");
        assertEquals("openallay:builder", extension.descriptor().id());
        assertEquals("0.2.1", extension.descriptor().version());
        assertEquals("[0.2.2,0.3)", extension.descriptor().openAllayApiVersionRange());
        var contribution = extension.contribution();
        assertEquals(Set.of("openallay_builder:building", "openallay_builder:terrain", "openallay_builder:presets"),
                contribution.javascriptModules().stream().map(module -> module.id()).collect(Collectors.toSet()));
        assertEquals(1, contribution.skills().size());
        var source = contribution.skills().getFirst();
        assertTrue(source.files().keySet().stream().noneMatch(path -> path.contains("/scripts/") || path.endsWith(".js")));
        var skill = new SkillParser().parse(source);
        assertEquals("minecraft-builder", skill.metadata().name());
        assertEquals(extension.descriptor().version(), skill.metadata().attributes().get("openallay/version"));
        assertEquals(BuilderBindings.WORLD_WRITE, skill.metadata().attributes().get("openallay/requires-capabilities"));
        assertEquals(Set.of(BuilderBindings.WORLD_WRITE), extension.descriptor().requirements().capabilities());
        assertEquals(List.of(BuilderBindings.MODULE), contribution.hostBindings().stream().map(binding -> binding.id()).toList());
        assertEquals(List.of(BuilderBindings.WORLD_WRITE), contribution.capabilities().stream().map(capability -> capability.id()).toList());
        assertEquals("openallay:builder", skill.metadata().attributes().get("openallay/requires-extensions"));
        assertTrue(skill.metadata().requiredMods().isEmpty());
        assertEquals(Set.of("openallay:run_javascript"), skill.metadata().allowedTools());
        assertEquals(Set.of("references/api.md", "references/geometry.md", "references/decoration.md",
                "references/terrain.md", "references/presets.md", "references/templates.md", "references/execution.md"),
                skill.references().keySet());
        assertTrue(skill.instructions().contains("require(\"openallay_builder:building\")"));
        assertTrue(skill.instructions().contains("b.finish()"));
    }


    @Test
    void onlyWorldChangingMethodsRequireTheIndependentBuilderWriteGrant() {
        var binding = new BuilderExtension("fabric").contribution().hostBindings().getFirst();
        assertEquals(Set.of("write", "writeRegion", "updateConnections", "syncPhysics", "undo"),
                binding.methods().stream().filter(method -> !method.requiredCapabilities().isEmpty())
                        .map(method -> method.name()).collect(Collectors.toSet()));
        for (var method : binding.methods()) {
            assertTrue(method.requiredCapabilities().isEmpty()
                    || method.requiredCapabilities().equals(Set.of(BuilderBindings.WORLD_WRITE)));
        }
        assertTrue(binding.methods().stream().anyMatch(method -> method.name().equals("open")
                && method.requiredCapabilities().isEmpty()));
        assertFalse(BuilderJsFixture.resource("building.js").contains("Java.type"));
    }

    @Test
    void packagedRegionExampleCountsEveryObservedCellInRealRhino() {
        String example = example("references/api.md", "var cells = b.read_region");
        var result = BuilderJsFixture.evaluate("""
                // Inject the detached backend at the documented online entry point.
                building.open=function(options){return building.create(backend,options);};
                for(var x=-2;x<=2;x++)for(var y=64;y<=66;y++)for(var z=-2;z<=2;z++)
                    seed(x,y,z,{id:y===64?'minecraft:stone_bricks':'minecraft:air',properties:{}});
                """ + "return (function(){\n" + example + "\n})();");

        assertEquals(75, result.get("observed").getAsInt());
        assertEquals(25, result.get("stoneBricks").getAsInt());
        assertEquals(Set.of("observed", "stoneBricks"), result.keySet());
    }

    @Test
    void packagedTerrainExampleAggregatesTheCompleteScanInRealRhino() {
        String example = example("references/terrain.md", "var terrain = builder.scan_ground");
        // The detached JS backend emulates full-height native scanning.
        // Relaxed test execution removes oracle loop budgets, not an API requirement.
        var result = BuilderJsFixture.evaluateUnrestricted("""
                building.open=function(options){return building.create(backend,options);};
                backend.read=function(x,y,z){return JSON.stringify({
                    id:y<=64?'minecraft:stone':'minecraft:air',properties:{}});};
                """ + "return (function(){\n" + example + "\n})();");

        var bounds = result.getAsJsonObject("bounds");
        assertEquals(289, bounds.get("count").getAsInt());
        assertEquals(289, bounds.get("found").getAsInt());
        assertEquals(0, bounds.get("missing").getAsInt());
        assertEquals(64, bounds.get("minY").getAsInt());
        assertEquals(64, bounds.get("maxY").getAsInt());
        assertEquals(289, result.getAsJsonObject("groundBlocks").get("minecraft:stone").getAsInt());
        assertEquals(Set.of("bounds", "groundBlocks"), result.keySet());
    }

    private static String example(String reference, String marker) {
        var source = new BuilderExtension("fabric").contribution().skills().getFirst();
        String document = new SkillParser().parse(source).references().get(reference);
        var examples = java.util.regex.Pattern.compile("```(?:javascript|js)\\R(.*?)\\R```",
                java.util.regex.Pattern.DOTALL).matcher(document);
        while (examples.find()) {
            if (examples.group(1).contains(marker)) return examples.group(1);
        }
        fail("Missing packaged JavaScript example: " + marker);
        return "";
    }
}
