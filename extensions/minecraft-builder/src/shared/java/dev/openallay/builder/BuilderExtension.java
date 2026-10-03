package dev.openallay.builder;

import dev.openallay.extension.JavascriptModuleSource;
import dev.openallay.extension.OpenAllayExtension;
import dev.openallay.extension.OpenAllayExtensionContribution;
import dev.openallay.extension.OpenAllayExtensionDescriptor;
import dev.openallay.skill.SkillSource;
import dev.openallay.requirement.RequirementSet;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** All domain behavior and resources are owned by this independent loader Extension. */
public final class BuilderExtension implements OpenAllayExtension {
    private final String loader;
    public BuilderExtension(String loader) { this.loader = loader; }
    @Override public OpenAllayExtensionDescriptor descriptor() {
        return new OpenAllayExtensionDescriptor("openallay:builder", "Minecraft Builder", "0.2.1",
                "OpenAllay", "Full online construction for the active integrated Minecraft server.",
                Set.of(loader), "[26.2,26.3)", "[0.2.2,0.3)", "https://github.com/nkanf-dev/OpenAllay-Extensions",
                new RequirementSet(Set.of(BuilderBindings.WORLD_WRITE), Set.of(), Set.of()));
    }
    @Override public OpenAllayExtensionContribution contribution() {
        String root = "assets/openallay_builder/";
        List<JavascriptModuleSource> modules = List.of("building", "presets", "terrain").stream()
                .map(name -> new JavascriptModuleSource("openallay_builder:"+name, resource(root+name+".js"))).toList();
        String skillRoot = "openallay_skills/minecraft-builder/";
        Map<String,String> files = new LinkedHashMap<>();
        files.put(skillRoot+"SKILL.md", resource(root+skillRoot+"SKILL.md"));
        for (String reference : List.of("api", "geometry", "decoration", "terrain", "presets", "templates", "execution")) {
            String path = skillRoot+"references/"+reference+".md";
            files.put(path, resource(root+path));
        }
        SkillSource skill = new SkillSource("openallay:builder", skillRoot+"SKILL.md", files, SkillSource.Origin.EXTERNAL);
        return new OpenAllayExtensionContribution(List.of(), modules, List.of(skill), List.of(),
                List.of(new BuilderParticipant(loader)), List.of(BuilderBindings.binding()), List.of(BuilderBindings.capability()));
    }
    private static String resource(String path) {
        try (InputStream input = BuilderExtension.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing Builder resource: "+path);
            return new String(input.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Could not load Builder resource: "+path, failure); }
    }
}
