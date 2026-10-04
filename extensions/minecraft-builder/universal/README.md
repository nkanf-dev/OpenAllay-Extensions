# Shared Builder universal module

This is the only runtime Builder module in the parent project. It compiles the
canonical `../src/shared/java` and `../src/shared/resources`; algorithms are not
copied for each Minecraft version or loader. Production bytecode is Java 8 and
uses the native-neutral public Extension SDK 0.3.0.

Run the checked-in wrapper from the parent project:

```text
./gradlew -PopenallayExtensionApiJar=/path/to/openallay-extension-api-0.3.0.jar build
```

The distributable `shadowJar` is `build/libs/openallay-builder-universal-0.3.0.jar`.
It privately relocates Gson 2.14.0 and does not bundle SDK, game, loader or Rhino
classes. The thin JAR is for build inspection only.

The same final payload has passed all 211 detached tests, native JVM 8 and 25
checks with the official older Gson in the parent, and actual current 26.2 Fabric
and NeoForge restricted Tool/world/template/journal/undo/shutdown proofs. Native
operations live in the core game adapter; the three old Extension-native classes
and separate Fabric/NeoForge wrapper projects are removed.

Current package support declares 26.2 Fabric and NeoForge only. The new shared
architecture is a foundation for stock Forge 1.12.2 and wider game support; those
cells are not claimed by this module until their native runtime/adapter proofs.
