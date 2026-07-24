# Authoring an OpenAllay Extension

An OpenAllay Extension is an ordinary Fabric or NeoForge mod. The loader creates
your entrypoint, your entrypoint registers one immutable declaration, and
OpenAllay validates the declaration before publishing any contribution.
OpenAllay does not scan classes or hot-load JARs.

## Package contract

Include `META-INF/openallay-extension.json` in the final JAR. Its identity,
version ranges, and `modIds` must agree with the mod metadata in
`fabric.mod.json` or `META-INF/neoforge.mods.toml`. Validate the source file
before packaging:

```bash
node scripts/validate-package-manifest.mjs path/to/openallay-extension.json
```

The manifest’s `openAllayApiVersionRange` is checked at game startup. Keep
OpenAllay as a required loader dependency and compile against the released API;
do not shade OpenAllay classes into the Extension.

Publish one normal JAR per loader. The embedded package manifest in the Fabric
JAR declares only `fabric`; the manifest in the NeoForge JAR declares only
`neoforge`. Both packages keep the same Extension ID and version. The community
catalog groups those loader-specific artifacts into one logical Extension
entry and selects only the current loader at install time.

## Fabric entrypoint

Declare a normal `main` entrypoint in `fabric.mod.json`, then register during
loader initialization:

```java
public final class ExampleFabricExtension implements ModInitializer {
    @Override
    public void onInitialize() {
        OpenAllayFabric.registerExtension(new ExampleExtension());
    }
}
```

## NeoForge entrypoint

Register from the ordinary mod constructor:

```java
@Mod("example_viewer_extension")
public final class ExampleNeoForgeExtension {
    public ExampleNeoForgeExtension(IEventBus modBus) {
        OpenAllayNeoForge.registerExtension(new ExampleExtension());
    }
}
```

Both entrypoints may share the same `OpenAllayExtension` implementation:

```java
public final class ExampleExtension implements OpenAllayExtension {
    @Override
    public OpenAllayExtensionDescriptor descriptor() {
        return new OpenAllayExtensionDescriptor(
                "example:viewer",
                "Example Viewer Extension",
                "0.1.0",
                "Example Author",
                "Adds typed viewer data to OpenAllay.",
                Set.of("fabric", "neoforge"),
                "[26.2,26.3)",
                "[0.2,0.3)",
                "https://github.com/example/openallay-viewer-extension");
    }

    @Override
    public OpenAllayExtensionContribution contribution() {
        return new OpenAllayExtensionContribution(
                List.of(exampleDataModule),
                List.of(exampleJavascriptModule),
                List.of(exampleSkill),
                List.of(exampleResultView));
    }
}
```

Each contribution ID is globally namespaced and immutable. Registration is
transactional: incompatible metadata, duplicate IDs, invalid Rhino-visible
types, or an invalid Skill reject the candidate without partially publishing
its other contributions.

## Runtime ownership

Capture Minecraft or mod API state on its owning game thread and detach it
before the Agent worker can see it. A `JavascriptDataModule` should project only
immutable records, immutable collections, or other explicitly supported closed
values. Do not retain live player, level, recipe manager, registry, UI, or
resource manager objects.

Optional dependencies fail independently. If an upstream mod is absent or its
public API capture fails, return a diagnostic capability state instead of
crashing OpenAllay bootstrap.

## Catalog submission

After publishing the loader-compatible JARs:

1. calculate the exact SHA-256 of every loader artifact;
2. ensure the shared catalog identity and version ranges match every embedded
   manifest;
3. ensure each artifact’s catalog `modIds` match that JAR’s embedded manifest;
4. add one deterministic schema-2 entry with an `artifacts` member for each
   supported loader to `catalog.json`;
5. run `node scripts/build-catalog.mjs`;
6. open a pull request with the source and release links.
