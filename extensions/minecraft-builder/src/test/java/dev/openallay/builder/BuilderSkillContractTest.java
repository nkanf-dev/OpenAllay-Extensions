package dev.openallay.builder;

import static org.junit.jupiter.api.Assertions.*;

import dev.openallay.skill.SkillParser;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/** Verifies the actual packaged Skill and reviewed module contribution, not copied documentation. */
final class BuilderSkillContractTest {
    @Test
    void extensionDeclaresAllModulesAndTheProgressivelyLoadedAdvisorySkill() {
        var extension = new BuilderExtension("fabric");
        assertEquals("openallay:builder", extension.descriptor().id());
        var contribution = extension.contribution();
        assertEquals(Set.of("openallay_builder:building", "openallay_builder:terrain", "openallay_builder:presets"),
                contribution.javascriptModules().stream().map(module -> module.id()).collect(Collectors.toSet()));
        assertEquals(1, contribution.skills().size());
        var source = contribution.skills().getFirst();
        assertTrue(source.files().keySet().stream().noneMatch(path -> path.contains("/scripts/") || path.endsWith(".js")));
        var skill = new SkillParser().parse(source);
        assertEquals("minecraft-builder", skill.metadata().name());
        assertEquals("unrestricted-javascript", skill.metadata().attributes().get("openallay/requires-capabilities"));
        assertEquals("openallay:builder", skill.metadata().attributes().get("openallay/requires-extensions"));
        assertTrue(skill.metadata().requiredMods().isEmpty());
        assertEquals(Set.of("openallay:run_javascript"), skill.metadata().allowedTools());
        assertEquals(Set.of("references/api.md", "references/geometry.md", "references/decoration.md",
                "references/terrain.md", "references/presets.md", "references/templates.md", "references/execution.md"),
                skill.references().keySet());
        assertTrue(skill.instructions().contains("require(\"openallay_builder:building\")"));
        assertTrue(skill.instructions().contains("b.finish()"));
    }
}
