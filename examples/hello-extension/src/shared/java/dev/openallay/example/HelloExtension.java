package dev.openallay.example;

import dev.openallay.context.DataAuthority;
import dev.openallay.context.DataCompleteness;
import dev.openallay.context.EvidenceMetadata;
import dev.openallay.context.ToolInvocationContext;
import dev.openallay.extension.OpenAllayExtension;
import dev.openallay.extension.OpenAllayExtensionContribution;
import dev.openallay.extension.OpenAllayExtensionDescriptor;
import dev.openallay.script.extension.JavascriptDataModule;
import java.lang.reflect.Type;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** One shared Extension implementation packaged by the two normal loader entrypoints. */
public final class HelloExtension implements OpenAllayExtension {
    private final String loader;

    public HelloExtension(String loader) {
        this.loader = loader;
    }

    @Override
    public OpenAllayExtensionDescriptor descriptor() {
        return new OpenAllayExtensionDescriptor(
                "example:hello",
                "Hello Extension",
                "0.1.2",
                "OpenAllay Community",
                "Exposes one typed, evidence-bearing JavaScript value.",
                Set.of(loader),
                "[26.2,26.3)",
                "[0.2,0.3)",
                "https://github.com/nkanf-dev/OpenAllay-Extensions");
    }

    @Override
    public OpenAllayExtensionContribution contribution() {
        return new OpenAllayExtensionContribution(
                List.of(new GreetingModule(loader)),
                List.of(),
                List.of(),
                List.of());
    }

    public record Greeting(String message, String loader) {}

    private record GreetingModule(String loader) implements JavascriptDataModule {
        @Override
        public String id() {
            return "example:greeting";
        }

        @Override
        public Type valueType() {
            return Greeting.class;
        }

        @Override
        public String summary() {
            return "A minimal typed value from an external OpenAllay Extension";
        }

        @Override
        public Snapshot capture(ToolInvocationContext context) {
            EvidenceMetadata evidence = new EvidenceMetadata(
                    DataAuthority.INTEGRATION_API,
                    DataCompleteness.COMPLETE,
                    context.capturedAt(),
                    "example:greeting",
                    "example:hello",
                    "26.2",
                    loader,
                    Map.of("extension", "example:hello"));
            return new Snapshot(
                    new Greeting("Hello from an external Extension", loader),
                    List.of(evidence));
        }
    }
}
