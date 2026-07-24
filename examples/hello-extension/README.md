# Hello Extension

This is a buildable external Extension, not an OpenAllay source-set fixture. It
shares one typed contribution implementation and produces separate normal
Fabric and NeoForge JARs.

Until the Extension SPI ships in the next OpenAllay `0.2.x` release, validate
the example against complete JARs from the adjacent OpenAllay checkout:

```bash
./gradlew :fabric:build :neoforge:build
./gradlew -p build/community/OpenAllay-Extensions/examples/hello-extension \
  -PopenallayArtifactsDir="$PWD" clean check
```

Without `openallayArtifactsDir`, the build resolves
`openallayVersion` (default `0.2.0`) from the public GitHub release. That route
is intentionally not claimed as passing until a release containing the SPI is
published; the original `0.2.0` predates it.

The resulting packages are under `build/libs/`. Each JAR contains only its
loader metadata and a loader-specific `META-INF/openallay-extension.json`.
After a normal restart, the example registers `mc.extensions["example:greeting"]`
with a typed `Greeting` record and evidence.
