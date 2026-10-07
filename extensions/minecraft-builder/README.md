# Minecraft Builder Extension

**Minecraft Builder 0.4.0** builds in your active
single-player world. It uses **Extension API 0.4.0** and one Java 8 universal
payload. OpenAllay supplies the native game adapters.

## Start building

1. Install the [latest OpenAllay release](https://github.com/nkanf-dev/OpenAllay/releases/latest)
   for your Minecraft version and loader. Follow the
   [compatibility guide](https://github.com/nkanf-dev/OpenAllay/blob/main/docs/native-binary-artifacts.md)
   for the required Java runtime and loader-specific setup. Forge 1.16.5 and
   1.12.2 use Java 17. Builder comes with OpenAllay.
2. Enter a single-player world and select a client-configured model profile.
3. Enable **Minecraft Builder** in **Settings → Extensions**.
4. Describe the build and location you want.

Enabling Builder includes its building operations in ordinary JavaScript mode.
Builder works in survival and creative worlds. The
**Enable full-access JavaScript** switch can stay off.
World edits use the active integrated server. Block undo restores matching
recorded after-images and reports conflicts with later edits.

## Tools and preset availability

Builder offers geometry, decoration, terrain, presets, and saved structure
templates. Templates support rotation, mirroring, and explicit air. The host
validates exact native block IDs and properties before writing.

Preset availability follows the game's material palette:

| Minecraft version and loader | Available building operations |
| --- | --- |
| Forge 1.16.5 | Geometry, decoration, terrain, templates, house, cottage, windmill, farm, and dock |
| Forge 1.12.2 | Geometry, native block variants, terrain paths, persisted templates, rotation, mirroring, and block undo |

On 1.16.5, choose one of the five presets listed above; the skyscraper preset
requires a lightning rod. On 1.12.2, build with geometry, terrain paths, and
templates. Its native material palette supports these operations; the six
structure presets require additional material roles. Check preset availability
and the native palette before selecting a preset.

## Shared implementation

Geometry, terrain, presets, templates, journals, Skills, and JavaScript live in
this repository. `MinecraftWorldAccess` and `WorldSession` provide block states,
NBT, world identity, loaded-chunk checks, and owner-thread scheduling. One
Extension payload contains the domain code; the core owns native differences.

Load the `minecraft-builder` Skill and its focused references. In ordinary
JavaScript, use `require('openallay_builder:building').open(options)`.
The host method binding is `openallay_builder:native`. Region work uses
cooperative slices. Only loaded chunks participate.

Opening is lazy and binds the exact active player, connection, and integrated
world. Read access leaves world identity unchanged. The first write or undo
initializes identity. Native operations check invocation lifetime and exact
world identity when they execute.

Templates and journals live under `config/openallay-builder/`. `finish`
completes a journal group. Cancelled or failed operations retain writes already
applied. Undo creates a new group and restores verified matching after-images.
Opaque native block-entity SNBT preserves the game's actual data. Templates and
journals cover their recorded blocks; retain world backups for complete world
and entity recovery.

## Build and install the universal package

Build the compatible SDK, then use the checked-in wrapper:

```text
/path/to/OpenAllay/gradlew -p /path/to/OpenAllay :extension-api:jar
./gradlew -PopenallayExtensionApiJar=/path/to/openallay-extension-api-0.4.0.jar build
```

The distributable JAR is
`universal/build/libs/openallay-builder-universal-0.4.0.jar`.
`verifyUniversalPackage` checks Java 8 classfiles, the external support manifest,
canonical resources, and private dependency isolation. Gson is privately
relocated; the host supplies SDK, game, loader, and Rhino classes.

For a separately supplied universal package, install the JAR in
`config/openallay/extensions/` and restart a compatible host. Universal packages
use this directory; legacy loader-mod packages use `mods/`.
An active same-ID community package takes precedence over the bundled payload.

## Native acceptance records

The accepted Forge 1.16.5 task package ran on Forge **36.2.42** and Java
**17.0.18+8**. Five presets and shared operations passed 80 native block checks;
reopening the original world preserved 13 journal operations and the template.

The accepted Forge 1.12.2 task package ran on Forge **14.23.5.2864** and Java
**17.0.18+8**. Normal bundled discovery loaded Builder. Native checks covered
geometry, terrain paths, template persistence and transformations, partial
failure, cancellation, undo, and conflicts. Both targets use restricted
JavaScript in survival worlds with cheats off.

Published downloads and setup instructions live in the
[core compatibility guide](https://github.com/nkanf-dev/OpenAllay/blob/main/docs/native-binary-artifacts.md).
The [core backport record](https://github.com/nkanf-dev/OpenAllay/blob/main/docs/verification/mature-forge-ecosystems.md)
tracks native acceptance and source provenance.
