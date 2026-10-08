# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Build / test commands

- `./gradlew build` — compiles, runs ktlint, and runs the test suite for every module.
- `./gradlew test` — runs tests only. The root `:test` task transitively triggers the recorder's GraalVM `nativeCompile` and a `publishToMavenLocal` (see *Test fixture wiring*), so a clean checkout's first run takes several minutes for the native image build.
- `./gradlew test --tests "ServiceAndMethodWrapperTests"` — single test class. (`-i` for info logging.)
- `./gradlew ktlintCheck` / `./gradlew ktlintFormat` — lint / autoformat. ktlint excludes everything under `build/` (workaround for the plugin not honoring generated-source exclusions). **Always run `ktlintFormat` after editing Kotlin source** so the next CI run isn't blocked on a trivial formatting failure.
- `./gradlew :protoc-utils-recorder:nativeCompile` — build the host's native binary directly (output: `recorder/build/native/nativeCompile/protoc-utils-recorder-<os>-<arch>.exe`). Requires GraalVM 21.
- `./gradlew publishAllPublicationsToGitHubPackagesRepository` — publishes both `:protoc-utils` and `:protoc-utils-recorder` to GitHub Packages. Requires `GITHUB_ACTOR` / `GITHUB_TOKEN` env vars (the CI release job sets these). The recorder publication picks up native binaries from `ENGINE_NATIVE_BIN_DIR` if set, else the local `nativeCompile` output (local-classifier only).
- `ENGINE_BUILD_VERSION=<semver> ./gradlew …` — sets `project.version` for the build. Falls back to `0.0.0-pre.0` if unset; CI release sets it from workflow_dispatch input.
- `./gradlew … -Pcodeql` — disables all `nativeCompile*` tasks (used by the CodeQL workflow which can't run native-image).

The `pr.yaml` workflow just calls `build.yaml` (which runs `./gradlew build` after `writeVersion`). Releases are cut by manually dispatching `release.yaml` with `build_version` + `pre_release`.

## Module layout

The repo is a multi-project Gradle build with three modules:

- **Root project `:protoc-utils`** (`src/main/kotlin/com/engine/protoc/util/…`) — the published Kotlin library. Pure JVM, no native code.
- **Subproject `:protoc-utils-recorder`** (`recorder/`) — a tiny GraalVM-native protoc plugin (single `Main.kt`) that copies stdin to a file named `code-generator-request.binpb`. It is shipped as multi-platform native binaries to Maven Central, **not** as a JVM jar.
- **Subproject `:protoc-utils-markdown`** (`markdown/`) — a JVM library, depending on the root library and commonmark-java, that resolves proto reference links in doc comments and converts comment Markdown to plain text. Kept separate so the core library stays free of the commonmark dependency.

`settings.gradle.kts` walks the tree and includes every directory containing a `build.gradle.kts` (skipping `build`, `buildSrc`, `tmp`, `scratch`, dotfile dirs, and dirs containing `.gradle_ignore`). Subproject names are derived as `:<root-name>-<relative-path>`, so the directory `recorder/` becomes `:protoc-utils-recorder`. To add a subproject, just create a directory with a `build.gradle.kts` — no manual `include(…)` needed.

## Library architecture

The library's job is to make protobuf's raw `DescriptorProto` types ergonomic to traverse in a protoc plugin. The shape of the API is dominated by three patterns:

- **`SyntaxElement<T>` pairs a scalar field with its `SourceCodeInfo.Location`.** Every `name`, `inputType`, `fieldNumber` on a descriptor is exposed as a `SyntaxElement<T>` instead of a bare value, so a plugin can recover the source comment / span without manually walking the opaque integer path that protoc uses to index `SourceCodeInfo.Location` records.
- **Every descriptor type has a matching `*Wrapper` class** (organized into packages by descriptor kind: `file/`, `service/`, `message/`, `enums/`, `compiler/`, etc.). All wrappers implement `GeneratedMessageWrapper<MessageT>` (exposing `.proto`) and most also implement `Locatable` (exposing `.location: LocationWrapper?`). Wrappers are constructed by the `extensions/*.kt` extension functions — most prominently `CodeGeneratorRequest.wrap()`, which is the entry point a plugin uses.
- **`*Options` wrappers extend `AbstractExtendableMessageWrapper`,** which adds `findExtension(extension)` for type-safe access to custom proto options (`google.api.http`, etc.) regardless of whether the extension was registered in the `ExtensionRegistry` at parse time. Unregistered extensions can also be recovered via `Message.findUnregisteredExtension(extension)` in `extensions/ExtendableMessage.kt`, which decodes them from `unknownFields`.

`explicitApi()` is enabled, so every exposed symbol must declare visibility (`public`/`internal`).

**When adding a new field to a wrapper class** that mirrors a field newly added in an upstream `descriptor.proto` (or `plugin.proto`, etc.), copy the field's documentation comment verbatim from the relevant `.proto` file into the KDoc of the new wrapper member. Upstream protobuf descriptor docs are the canonical source of truth for what each field means; restating them lets plugin authors stay in our API surface without context-switching to `descriptor.proto`.

## Test fixture wiring (important!)

The test suite consumes a `code-generator-request.binpb` fixture generated by the recorder plugin at build time, **not** by invoking protoc at test time. The chain is:

1. `:generateTestProto` (the protobuf Gradle plugin) wants the recorder as a `protoc` plugin.
2. It is configured at `build.gradle.kts` to resolve the recorder via Maven coordinates: `artifact = "$group:${projects.protocUtilsRecorder.name}:${version}"`. The protobuf plugin appends `:${osdetector.classifier}@exe` at resolution time.
3. `mavenLocal()` is registered at the root with `content.includeModule` restricting it to **only** `com.engine:protoc-utils-recorder` — everything else still goes through `mavenCentral`.
4. `:generateTestProto` `dependsOn(":protoc-utils-recorder:publishToMavenLocal")`. The recorder's `MavenPublication` declares its local-classifier artifact `builtBy(tasks.named("nativeCompile"))`, so a fresh build chain is `nativeCompile` → `publishToMavenLocal` → `generateTestProto` → `:test`.
5. The recorder writes `code-generator-request.binpb` into `build/generated/sources/proto/test/recorder/`; `processTestResources` copies it into the test classpath.

If you change anything about the recorder publication, the build script's `repositories { mavenLocal { … } }` filter, or the protobuf plugin's `artifact = …` coordinate, exercise a clean test run (`rm -rf ~/.m2/repository/com/engine/protoc-utils-recorder recorder/build/native && ./gradlew test`) before pushing — Bugbot has flagged this chain twice already.

## Publishing model

Releases publish to [GitHub Packages](https://github.com/engine-public/protoc-utils/packages) via the standard `maven-publish` plugin, configured in the root `build.gradle.kts`:

- **`com.engine:protoc-utils`** ships as a normal JVM library (jar + sources + javadoc + `.module`). `withJavadocJar()` / `withSourcesJar()` are applied **only** to the root project — not in `allprojects` — so the recorder doesn't waste cycles building javadoc/sources jars it would never publish.
- **`com.engine:protoc-utils-recorder`** is `<packaging>pom</packaging>` with four classifier attachments (`linux-x86_64`, `linux-aarch_64`, `osx-aarch_64`, `windows-x86_64`), all with `.exe` extension regardless of host OS. This matches the `io.grpc:protoc-gen-grpc-java` convention so the protobuf Gradle plugin's `:${osdetector.classifier}@exe` resolution works out-of-the-box. The recorder's `imageName` conditionally appends `.exe` only on non-Windows hosts (GraalVM auto-appends on Windows).

The CI release flow is `release.yaml` → fan out to `build.yaml` (JVM jars) and `native-build.yaml` (4-platform matrix producing `dist-native-<classifier>` artifacts named `protoc-utils-recorder-<version>-<classifier>.exe`) → release job downloads everything flat, then runs `./gradlew publishAllPublicationsToGitHubPackagesRepository` (with `ENGINE_NATIVE_BIN_DIR=build/dist/native`). The same binaries are also attached to a GitHub Release via `gh release upload`.

The release job needs `packages: write` permission; the workflow's default `GITHUB_TOKEN` handles authentication — no separate secrets required. Consumers of GitHub Packages Maven repos need a personal access token with `read:packages` scope, even though the repository is public (this is a known GitHub limitation).

## Dependency versions

`ch.qos.logback:logback-*` is force-resolved to `[1.5.25,)` via a `configurations.named("ktlint")` rule to address a known dependabot CVE. All other versions live in `gradle/libs.versions.toml`.