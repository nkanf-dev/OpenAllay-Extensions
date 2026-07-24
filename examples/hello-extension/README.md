# Hello Extension

This is a buildable external Extension, not an OpenAllay source-set fixture. It
shares one typed contribution implementation and produces separate normal
Fabric and NeoForge JARs.

By default, the example resolves the released OpenAllay `0.2.1` Fabric and
NeoForge JARs directly from GitHub Releases. OpenAllay product versions and
Extension API versions are independent: this package requires OpenAllay
`0.2.1` or newer while declaring compatibility with Extension API `0.2.x`.

```bash
gradle clean check
```

Use `-PopenallayVersion=<version>` to validate another released API, or
`-PopenallayArtifactsDir=<checkout>` while developing OpenAllay itself.

The resulting packages are under `build/libs/`. Each JAR contains only its
loader metadata and a loader-specific `META-INF/openallay-extension.json`.
After a normal restart, the example registers `mc.extensions["example:greeting"]`
with a typed `Greeting` record and evidence.
