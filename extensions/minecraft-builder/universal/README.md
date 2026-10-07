# Shared Builder universal module

This module builds the canonical `../src/shared/java` and
`../src/shared/resources` into one universal **Builder 0.4.0** JAR. Production
bytecode targets Java 8 and uses the native-neutral public SDK **0.4.0**.
The core supplies game-specific operations through `MinecraftWorldAccess` and
`WorldSession`.

Run the checked-in wrapper from the parent project:

```text
./gradlew -PopenallayExtensionApiJar=/path/to/openallay-extension-api-0.4.0.jar build
```

The distributable `shadowJar` is
`build/libs/openallay-builder-universal-0.4.0.jar`. It privately relocates Gson
and keeps SDK, game, loader, and Rhino classes outside the payload. Package
verification checks canonical resources, classfile targets, dependency
isolation, and the exact support manifest.

Enabling Builder includes its building operations in ordinary JavaScript mode.
Its algorithms, Skills, templates, and journals stay shared across declared
native hosts. Native material palettes determine preset availability.
See the [Builder guide](../README.md) for supported operations, installation,
permissions, and accepted native-host records.
