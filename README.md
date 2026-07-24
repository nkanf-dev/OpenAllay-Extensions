# OpenAllay Extensions

Community catalog and authoring examples for
[OpenAllay](https://github.com/nkanf-dev/OpenAllay), the modern Minecraft Agent.

OpenAllay Extensions connect mod APIs to OpenAllay’s typed JavaScript host.
They are ordinary Fabric or NeoForge mods: after installation, Minecraft loads
them on restart and OpenAllay discovers the detached capabilities they expose.

## Catalog

[`catalog.json`](catalog.json) is the stable catalog consumed by OpenAllay’s
in-game Extensions page. The catalog is intentionally empty until the public
Extension API is available from a released OpenAllay 0.2.x artifact. This keeps
the community index installable and truthful while the first external example
is prepared.

Catalog entries are strict and must include:

- a stable Extension ID and version;
- supported loaders and Minecraft/OpenAllay version ranges;
- an HTTPS artifact URL and exact SHA-256 checksum;
- the mod IDs installed by the artifact;
- a public source location.

See [`schema/catalog.schema.json`](schema/catalog.schema.json) for the complete
format.

## Contributing

The first SDK example and submission guide will land with the released public
Extension API. Until then, design discussion and compatibility requests are
welcome through GitHub issues.

An Extension must:

- detach Minecraft or mod state before exposing it to the Agent;
- provide typed, documented JavaScript data and operations;
- support Fabric and NeoForge when the upstream integration exists on both;
- fail independently when its optional mod dependency is missing or changes.

## License

Catalog metadata and repository tooling are available under the MIT License.
Individual Extension artifacts may declare their own compatible licenses.
