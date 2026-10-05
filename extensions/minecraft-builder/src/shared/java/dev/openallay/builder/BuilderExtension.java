package dev.openallay.builder;

import dev.openallay.api.extension.ExtensionContribution;
import dev.openallay.api.extension.ExtensionDescriptor;
import dev.openallay.api.extension.ExtensionHost;
import dev.openallay.api.extension.ExtensionRequirements;
import dev.openallay.api.extension.JavascriptModuleSource;
import dev.openallay.api.extension.OpenAllayExtension;
import dev.openallay.api.extension.SkillSource;
import dev.openallay.api.extension.SupportDeclaration;
import dev.openallay.api.extension.SupportTarget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One Java 8 domain payload. Native world access belongs to the host, not a loader entrypoint. */
public final class BuilderExtension implements OpenAllayExtension {
    public BuilderExtension() {}
    @Override public ExtensionDescriptor descriptor() {
        List<SupportTarget> targets = new ArrayList<SupportTarget>();
        // One payload and public API across the prepared native target profiles.
        for (String loader : Arrays.asList("fabric", "neoforge")) {
            for (String game : Arrays.asList("1.20.1", "1.20.2", "1.20.3", "1.20.4", "1.20.5", "1.20.6", "1.21", "1.21.1", "1.21.2", "1.21.3", "1.21.4", "1.21.5", "1.21.6", "1.21.7", "1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.1.1", "26.1.2", "26.2", "26.3")) {
                targets.add(new SupportTarget(loader, game, "[0.4.1,)", "[0.4.0,0.5.0)"));
            }
        }
        return new ExtensionDescriptor("openallay:builder", "Minecraft Builder", "0.4.0",
                "OpenAllay", "Full online construction for the active integrated Minecraft server.",
                "https://github.com/nkanf-dev/OpenAllay-Extensions",
                new SupportDeclaration(targets, 8, Collections.singleton("minecraft:world-access"),
                        Collections.<String>emptySet()),
                ExtensionRequirements.EMPTY);
    }
    @Override public ExtensionContribution contribution(ExtensionHost host) {
        String root = "assets/openallay_builder/";
        List<JavascriptModuleSource> modules = new ArrayList<JavascriptModuleSource>();
        for (String name : Arrays.asList("building", "presets", "terrain"))
            modules.add(new JavascriptModuleSource("openallay_builder:" + name, resource(root + name + ".js")));
        String skillRoot = "openallay_skills/minecraft-builder/";
        Map<String,String> files = new LinkedHashMap<String,String>();
        files.put(skillRoot + "SKILL.md", resource(root + skillRoot + "SKILL.md"));
        for (String reference : Arrays.asList("api", "geometry", "decoration", "terrain", "presets", "templates", "execution")) {
            String path = skillRoot + "references/" + reference + ".md";
            files.put(path, resource(root + path));
        }
        SkillSource skill = new SkillSource("openallay:builder", skillRoot + "SKILL.md", files, SkillSource.Origin.EXTERNAL);
        return new ExtensionContribution(modules, Collections.singletonList(skill), Collections.emptyList(),
                Collections.singletonList(new BuilderParticipant(host)), Collections.singletonList(BuilderBindings.binding()));
    }
    private static String resource(String path) {
        try (InputStream input = BuilderExtension.class.getClassLoader().getResourceAsStream(path)) {
            if (input == null) throw new IllegalStateException("Missing Builder resource: " + path);
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) != -1;) output.write(buffer, 0, count);
            return new String(output.toByteArray(), StandardCharsets.UTF_8);
        } catch (IOException failure) { throw new IllegalStateException("Could not load Builder resource: " + path, failure); }
    }
}
