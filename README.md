# OpenAllay Extensions

Extensions and authoring examples for
[OpenAllay](https://github.com/nkanf-dev/OpenAllay), your AI companion in Minecraft.

Extensions connect game data, reusable JavaScript modules, mod integrations,
actions, Skills, and native result views. The public **Extension API 0.4.0**
provides a native-neutral Java 8 SDK. One universal Extension can use the host's
game adapters across its declared Minecraft and loader targets.

## Use an Extension

Open **OpenAllay Settings → Extensions** to inspect and enable installed
Extensions. The Community page offers compatible catalog packages and local
JAR import. Follow the package instructions for its installation directory,
then restart Minecraft.

| Package type | Installation directory | Host contract |
| --- | --- | --- |
| Universal Extension | `config/openallay/extensions/` | Extension API 0.4.0; one native-neutral JAR |
| Legacy loader-mod Extension | `mods/` | The package's declared API 0.2.x and Fabric/NeoForge targets |

Each distributable JAR contains `META-INF/openallay-extension.json`. OpenAllay
checks its identity, supported targets, API range, and required host features
before activating it. The package's installation instructions identify its type.

## Minecraft Builder

[**Minecraft Builder**](extensions/minecraft-builder) ships with OpenAllay.
Builder **0.4.0** uses the public SDK **0.4.0**. Geometry, terrain, presets,
templates, journals, Skills, and JavaScript share one Java 8 implementation.
The core supplies native block states, NBT, world identity, and game-thread
scheduling.

Enter a single-player world, select your client-configured model, and enable
**Minecraft Builder** in **Settings → Extensions**. Enabling Builder includes
its building operations in ordinary JavaScript mode. It works in survival and
creative worlds. The **Enable full-access JavaScript** switch can stay off.

Available presets follow the game's native material palette. Geometry, terrain,
and saved templates can use exact block states available in that instance.
See the [Builder guide](extensions/minecraft-builder/README.md) for its modules,
preset availability, journals, and undo behavior.

## Build a universal Extension

Compile against `dev.openallay:openallay-extension-api:0.4.0` as a compile-only
dependency. Keep Minecraft, loader, and SDK classes outside your payload.
Implement `dev.openallay.api.extension.OpenAllayExtension`, declare exact
supported targets, and package one self-contained JAR.

Read the [authoring guide](docs/authoring.md) and the
[core SDK contract](https://github.com/nkanf-dev/OpenAllay/blob/main/docs/universal-extensions.md).
The Builder [source lock](extensions/minecraft-builder/openallay-source.lock.json)
pins the SDK source used by its quality workflow.

## Community catalog and examples

[`catalog.json`](catalog.json) feeds OpenAllay's Extensions Community page.
Its entries bind a published artifact to its exact SHA-256, compatibility
ranges, source, and package identity. Submit metadata after publishing the
matching package. See [`schema/catalog.schema.json`](schema/catalog.schema.json)
for the current catalog fields.

[`examples/hello-extension`](examples/hello-extension) is the independently
released legacy API 0.2.x example. It builds separate Fabric and NeoForge mods
against OpenAllay 0.2.1. Follow its loader entrypoints and `mods/` installation
instructions when working with API 0.2.x. New universal packages use SDK 0.4.0 and
startup discovery from `config/openallay/extensions/`.

## Contributing

- Detach game or mod data before exposing it to the Agent.
- Provide typed, documented JavaScript data and operations.
- Declare the exact targets and host features used by your package.
- Keep native differences in the core or integration adapter; reuse shared
  domain code across supported hosts.
- Report an unavailable optional integration independently so other features
  remain usable.
- Version your Extension and public API compatibility independently from the
  OpenAllay product.

## License

Catalog metadata and repository tooling use the MIT License. Individual
Extension artifacts declare their own licenses.
