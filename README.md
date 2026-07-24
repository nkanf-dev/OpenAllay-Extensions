# OpenAllay Extensions

Community catalog and authoring examples for
[OpenAllay](https://github.com/nkanf-dev/OpenAllay), the modern Minecraft Agent.

OpenAllay Extensions connect mod APIs to OpenAllay’s typed JavaScript host.
They are ordinary Fabric or NeoForge mods: after installation, Minecraft loads
them on restart and OpenAllay discovers the detached capabilities they expose.

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
compiles against the released OpenAllay `0.2.1` API and produces independent
Fabric and NeoForge packages.

## Catalog

[`catalog.json`](catalog.json) is the stable catalog consumed by OpenAllay’s
in-game Extensions page. Published entries point to loader-specific,
checksum-pinned release artifacts. The first entry is the installable Hello
Extension reference package for both Fabric and NeoForge.

Catalog entries are strict and must include:

- a stable Extension ID and version;
- Minecraft/OpenAllay version ranges;
- one HTTPS artifact, exact SHA-256 checksum, and installed mod-ID set for each
  supported loader;
- a public source location.

See [`schema/catalog.schema.json`](schema/catalog.schema.json) for the complete
format. Fabric and NeoForge normally publish different JARs, so schema 2 keeps
them under one logical Extension version but verifies and installs only the
artifact for the current loader. Each loader artifact must describe the same
identity and compatibility ranges as the manifest embedded in that JAR, and
its catalog `modIds` must match the embedded manifest.

## Contributing

The public Extension API is versioned independently from the catalog. Compile
against a released OpenAllay `0.2.x` artifact, declare the supported API range
in the embedded manifest, and keep OpenAllay as a loader dependency rather than
bundling its classes into your JAR.

An Extension must:

- detach Minecraft or mod state before exposing it to the Agent;
- provide typed, documented JavaScript data and operations;
- support Fabric and NeoForge when the upstream integration exists on both;
- fail independently when its optional mod dependency is missing or changes.

## License

Catalog metadata and repository tooling are available under the MIT License.
Individual Extension artifacts may declare their own compatible licenses.
