# OpenAllay Extensions

Community catalog and authoring examples for
[OpenAllay](https://github.com/nkanf-dev/OpenAllay), the modern Minecraft Agent.

OpenAllay Extensions connect game capabilities to OpenAllay’s typed JavaScript host.
The native-neutral Extension API 0.3.0 supports one Java-8 payload loaded by the core
at startup. Existing API 0.2.x loader-mod Extensions keep their declared compatibility
and ordinary Fabric/NeoForge installation path.

Every distributable Extension JAR embeds a strict package manifest at
`META-INF/openallay-extension.json`. This lets OpenAllay validate and import a
local JAR even when it is not listed in the community catalog. See
[`schema/package-manifest.schema.json`](schema/package-manifest.schema.json)
and the [example manifest](examples/openallay-extension.json). Extension
entrypoints register through the normal Fabric or NeoForge loader lifecycle;
the complete startup contract and minimal examples are in the
[authoring guide](docs/authoring.md). A complete independent project that
builds both loader JARs is available under
[`examples/hello-extension`](examples/hello-extension). Its CI currently
compiles against the released OpenAllay `0.2.1` product artifacts and produces
independent Fabric and NeoForge packages. OpenAllay product versions and
Extension API versions are independent; `0.2.1` currently implements Extension
API `0.2.0`.

## First-party Minecraft Builder

[`extensions/minecraft-builder`](extensions/minecraft-builder) builds **one universal
Builder 0.3.0 JAR** against standalone public SDK 0.3.0. Geometry, terrain, templates,
journals, Skills and JavaScript stay in one canonical Java-8 domain implementation.
Minecraft-specific owner scheduling, registries, NBT and world identity belong to the
core's game adapters, not a separate Extension for each game or loader.

The final privately shaded payload has run unchanged on Java 8 and Java 25, and in
actual Minecraft 26.2 Fabric and NeoForge development clients. The current game-host
support declaration covers those two verified cells only; Java-8 bytecode alone does
not claim stock Forge 1.12.2 or a newer game's native compatibility.

World writes still require the independent Builder grant, off by default and frozen
per request. Installation, host feature availability and read access do not grant it.
See the [Builder README](extensions/minecraft-builder/README.md) for current build,
package, native adapter and verification contracts. The immutable published core
0.4.1 release still bundles legacy Builder 0.2.1; later source integration is separate.

The [source lock](extensions/minecraft-builder/openallay-source.lock.json) pins the
actual public SDK implementation. The Builder quality workflow builds that SDK and
tests one native-free universal package. Public catalog entries remain tied to
published checksum-verified artifacts, not merely committed source.

## Catalog

[`catalog.json`](catalog.json) is the stable catalog consumed by OpenAllay’s
in-game Extensions page. Published entries point to loader-specific,
checksum-pinned release artifacts. The first entry is the installable Hello
Extension reference package for both Fabric and NeoForge.

Catalog entries are strict and must include:

- a stable Extension ID and version;
- Minecraft and OpenAllay Extension API version ranges;
- one HTTPS artifact, exact SHA-256 checksum, and installed mod-ID set for each
  supported loader;
- a public source location.

See [`schema/catalog.schema.json`](schema/catalog.schema.json) for the complete
format. Fabric and NeoForge normally publish different JARs, so the current shape keeps
them under one logical Extension version but verifies and installs only the
artifact for the current loader. Each loader artifact must describe the same
identity and compatibility ranges as the manifest embedded in that JAR, and
its catalog `modIds` must match the embedded manifest.

## Contributing

The public Extension API is versioned independently from both the catalog and
the OpenAllay product. Compile against a released OpenAllay `0.2.x` artifact,
declare the supported Extension API range in the embedded manifest, and declare
the minimum OpenAllay product version in Fabric or NeoForge dependency metadata.
Keep OpenAllay as a loader dependency rather than bundling its classes into
your JAR.

An Extension must:

- detach Minecraft or mod state before exposing it to the Agent;
- provide typed, documented JavaScript data and operations;
- support Fabric and NeoForge when the upstream integration exists on both;
- fail independently when its optional mod dependency is missing or changes.

## License

Catalog metadata and repository tooling are available under the MIT License.
Individual Extension artifacts may declare their own compatible licenses.
