# Authoring an OpenAllay Extension

## Universal Extension API 0.4.0

Compile against the standalone Java 8 SDK
`dev.openallay:openallay-extension-api:0.4.0` as a compile-only dependency.
Implement `dev.openallay.api.extension.OpenAllayExtension` with a public
no-argument constructor. Keep game, loader, Rhino, and SDK classes outside
your self-contained payload. Privately shade dependencies your Extension owns.

The core discovers the JAR at startup from `config/openallay/extensions/`.
Its `META-INF/openallay-extension.json` names the entrypoint and declares an
explicit union of Minecraft, loader, product, and public API compatibility
coordinates. It also declares minimum Java and required host features.
The manifest and Java descriptor must agree.

`descriptor()` returns immutable identity and support metadata without touching
a world. `contribution(ExtensionHost)` returns JavaScript modules, Skills,
result-view declarations, invocation participants, and controlled host bindings.
Pure JavaScript and Skill contributions can work without a native world adapter.

`JavascriptHostMethod.Invoker` accepts detached JSON arguments as Strings and
returns one JSON value as a String. The core validates and detaches values.
An `ExtensionInvocation` binds identity, cancellation, and evidence to one
request. Capture native state lazily through the host and perform each operation
on its owning game thread.

After you enable an Extension, its exposed operations are available in ordinary
JavaScript mode. Enabling Minecraft Builder includes building and world writes.
Full-access JavaScript includes game commands and enabled Extension operations.
Minecraft checks the actual player connection and server permissions. Cancellation, closed invocations,
and world lifetime remain operation-validity checks.

Read the [complete manifest and lifecycle contract](https://github.com/nkanf-dev/OpenAllay/blob/main/docs/universal-extensions.md)
and [Builder implementation guide](../extensions/minecraft-builder/README.md).
Use the standalone SDK declarations and package tests as the source for public
API signatures. Match every declared target to its actual game adapter and
available blocks or properties.

## Existing loader-mod API 0.2.x

The following sections describe the independently released API 0.2.x example
and loader-mod package contract. Use them for an Extension targeting that API
and its declared Minecraft and loader versions. For a new universal Extension,
use the SDK 0.4.0 instructions above and the linked manifest and lifecycle contract.

An API 0.2.x Extension is an ordinary Fabric or NeoForge mod. The loader creates
your entrypoint, your entrypoint registers one immutable declaration, and
OpenAllay validates the declaration before publishing any contribution.
The loader registers the Extension during startup. Restart Minecraft after
installing its JAR.

## Legacy API 0.2.x package contract

Include `META-INF/openallay-extension.json` in the final JAR. Its identity,
version ranges, and `modIds` must agree with the mod metadata in
`fabric.mod.json` or `META-INF/neoforge.mods.toml`. Validate the source file
before packaging:

```bash
node scripts/validate-package-manifest.mjs path/to/openallay-extension.json
```

The manifest’s `openAllayApiVersionRange` is checked against OpenAllay's
independently versioned Extension API at game startup. Keep OpenAllay as a
required loader dependency, set the minimum product version in the loader
metadata, and compile against that released product artifact; do not shade
OpenAllay classes into the Extension. For example, OpenAllay product `0.2.1`
implements Extension API `0.2.0`, so the Hello Extension uses API range
`[0.2,0.3)` and loader dependency `>=0.2.1`.

Publish one normal JAR per loader. The embedded package manifest in the Fabric
JAR declares only `fabric`; the manifest in the NeoForge JAR declares only
`neoforge`. Both packages keep the same Extension ID and version. The community
catalog groups those loader-specific artifacts into one logical Extension
entry and selects only the current loader at install time.

## Legacy API 0.2.x advisory requirements

Schema-1 package manifests and schema-2 catalog entries may include an optional
`requirements` object. It describes useful capabilities, Extensions, and Skills
for the player. For example:

```json
"requirements": {
  "capabilities": ["example:viewer-api"],
  "extensions": ["example:viewer"],
  "skills": ["example-workflow"]
}
```

Each member is an array of unique, exact IDs. IDs cannot contain spaces,
uppercase letters, wildcards, or version expressions. The full ID must match:

- `capabilities`: `[a-z0-9][a-z0-9_.-]*(?::[a-z0-9_][a-z0-9_./-]*)?`;
- `extensions`: `[a-z0-9_.-]+:[a-z0-9_./-]+`;
- `skills`: `[a-z0-9]+(?:-[a-z0-9]+)*`, at most 64 characters.

Unknown capability IDs remain valid declarations. Capability IDs describe
availability; player settings and Minecraft permissions control operations.
Absent members mean empty lists. Unknown members, duplicate IDs,
invalid IDs, and non-array values are rejected. Omit `requirements` when all
lists are empty. The catalog builder omits empty lists and the all-empty object;
it preserves declared ID order. Entries without requirements keep their prior
output. Other unknown fields and unsupported schema versions remain invalid.

Requirements are advisory: they show useful capabilities, Extensions, and Skills
to the player. Installation, activation, use, settings, dependencies, and
permissions follow their existing controls independently of this metadata.
API availability comes from the installed host and integrations. Existing loader
dependencies and Skill `required-mods` and `allowed-tools` keep their separate contracts. Keep catalog declarations
consistent with the selected package where possible. After staging, the checked
package manifest is the source for advisory display; differences in requirements
alone are not identity mismatches. Identity, compatibility, checksums, and mod-ID
checks still apply.

Run the focused tooling tests without building any loader artifacts:

```bash
node --test scripts/requirements.test.mjs
```

## Legacy API 0.2.x Fabric entrypoint

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

## Legacy API 0.2.x NeoForge entrypoint

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

## Legacy API 0.2.x runtime ownership

Capture Minecraft or mod API state on its owning game thread and detach it
before the Agent worker can see it. A `JavascriptDataModule` should project only
immutable records, immutable collections, or other explicitly supported closed
values. Do not retain live player, level, recipe manager, registry, UI, or
resource manager objects.

Optional dependencies fail independently. If an upstream mod is absent or its
public API capture fails, return a diagnostic capability state instead of
crashing OpenAllay bootstrap.

## Legacy API 0.2.x catalog submission

After publishing the loader-compatible JARs:

1. calculate the exact SHA-256 of every loader artifact;
2. ensure the shared catalog identity and version ranges match every embedded
   manifest;
3. ensure each artifact’s catalog `modIds` match that JAR’s embedded manifest;
4. add one deterministic schema-2 entry with an `artifacts` member for each
   supported loader to `catalog.json`;
5. run `node scripts/build-catalog.mjs`;
6. open a pull request with the source and release links.
