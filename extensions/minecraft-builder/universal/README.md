# Shared Builder universal module

This is the only runtime Builder module in the parent project. It compiles the
canonical `../src/shared/java` and `../src/shared/resources`; algorithms are not
copied for each Minecraft version or loader. Production bytecode is Java 8 and
uses the native-neutral public Extension SDK 0.4.0.

Run the checked-in wrapper from the parent project:

```text
./gradlew -PopenallayExtensionApiJar=/path/to/openallay-extension-api-0.4.0.jar build
```

The distributable `shadowJar` is `build/libs/openallay-builder-universal-0.4.0.jar`.
It privately relocates Gson 2.14.0 and does not bundle SDK, game, loader or Rhino
classes. The thin JAR is for build inspection only.

The prior Builder 0.3.0 universal payload passed 211 detached tests, native JVM 8
and 25 checks with the official older Gson in the parent, and 26.2 Fabric and
NeoForge restricted Tool/world/template/journal/undo/shutdown proofs. Builder
0.4.0 uses the new SDK contract without an Extension-private write gate. Enabling
Builder enables its building operations in restricted JavaScript without JVM
access. Native operations remain in the core game adapter; there are no
per-version algorithm copies or separate Fabric/NeoForge wrapper projects.

Current package support declares 26.2 Fabric and NeoForge only. The new shared
architecture is a foundation for stock Forge 1.12.2 and wider game support; those
cells are not claimed by this module until their native runtime/adapter proofs.
