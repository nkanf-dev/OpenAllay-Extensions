# Builder Scoped Authority Implementation Plan

**Goal:** Let normal restricted JavaScript use an explicitly authorized Builder interface without granting the Agent JVM access.

**Architecture:** Builder contributes an invocation-local method module through the public Extension API. The module accepts detached JSON values and returns opaque session IDs or detached results. The native adapter keeps its current owner-thread, exact identity, cancellation, loaded-chunk, journaling and undo checks.

**Tech Stack:** Java 25, the public OpenAllay Extension API, existing Rhino method callbacks, CommonJS modules, JUnit 5.

## Owned files

- `extensions/minecraft-builder/src/shared/java/dev/openallay/builder/BuilderRuntime.java`: scope-local sessions and method dispatch, no unrestricted-JVM gate.
- `extensions/minecraft-builder/src/shared/java/dev/openallay/builder/BuilderParticipant.java`: bind methods through the core API only after independent native/world-action authorization.
- `extensions/minecraft-builder/src/shared/resources/assets/openallay_builder/building.js`: normal CommonJS opening through the detached method module.
- Builder descriptor, loader manifests, Skill, README and contract tests: declare the exact new capability without suggesting JVM authority.
- Dedicated method and JavaScript contracts: detached handles, strict argument shape, normal restricted operation and unavailable authorization.

## Steps

- [x] Agree with the core worker on one public method-module API and one independently configured native/world-action capability. Installation and read-only world observation must not authorize writes.
- [x] Add tests for a restricted `building.open()` through a detached method module, missing module/authorization, unknown session IDs, exact argument types and retained facade revocation.
- [x] Replace `Java.type` and the `unrestrictedJavascript` gate with scope-owned callbacks. Never return a Java object or accept script callbacks in native methods.
- [x] Keep all native performance algorithms, scheduling quanta, identity validation, journal formats and undo behavior unchanged.
- [x] Update advisory requirements and user documentation. Keep public Extension API compatibility separately versioned. Parent owns source publication and core pin changes.
- [x] Run static source/manifest checks now. Gradle, game, provider and secret configuration access are prohibited until the parent explicitly opens verification.
- [x] Ask for independent review. Report the exact changes and deferred verification. Do not commit or push.

## Verification result

Parent opened exclusive Gradle slots for the intermediate API gate and the final integrated-core gate. All 221 common tests, both loader builds and package verification passed again against the final integrated API 0.2.2 development JAR (SHA-256 `f056efa9ca3fb0bd8ff27ccbc399db18876abd12c4bcd1cb871bf625ca19444a`). See `docs/builder-scoped-authority-review.md` for the command, SHA-256, exact limits and publication dependency. No game or model provider was launched.

The release-metadata gate also passed: actual OpenAllay 0.3.0 JAR SHA-256 `4b4ec127cbb7c94f1de2989cb396ddb04196bcfcc1fbe3375d9b82e0a2b13cfb`, Builder 0.2.0, all 221 tests, both loaders and package verification. The real published source pin remains parent-owned.
