# protoc-utils-recorder

A protoc plugin that captures the raw `CodeGeneratorRequest` bytes sent to it by `protoc` and writes them back out as a `CodeGeneratorResponse` file named `code-generator-request.binpb`. The plugin does **not** deserialize the request, so standard plugin options are ignored (but preserved verbatim in the output).

The primary use case is generating a stable binary fixture for unit-testing a protoc plugin: run the recorder once against your `.proto` files, commit the `.binpb` output as a test resource, then replay it in tests without needing `protoc` at test time.

---

## Maven Central

The recorder is published to Maven Central as a POM-only artifact with a per-platform native binary attached as a classified file. Coordinates:

| Group | Artifact | Classifier | Extension |
|---|---|---|---|
| `com.engine` | `protoc-utils-recorder` | `linux-x86_64` | `exe` |
| `com.engine` | `protoc-utils-recorder` | `linux-aarch_64` | `exe` |
| `com.engine` | `protoc-utils-recorder` | `osx-aarch_64` | `exe` |
| `com.engine` | `protoc-utils-recorder` | `windows-x86_64` | `exe` |

Every binary uses the `.exe` extension, regardless of platform, matching the convention used by `io.grpc:protoc-gen-grpc-java` and `io.github.pseudomuto:protoc-gen-doc`. The classifier values match the `com.google.osdetector` Gradle plugin's `osdetector.classifier` exactly, so the protobuf Gradle plugin can resolve the right binary for the build host directly:

```kotlin
plugins {
    id("com.google.protobuf")
}

protobuf {
    plugins {
        create("recorder") {
            artifact = "com.engine:protoc-utils-recorder:<version>"
        }
    }
}
```

The protobuf-gradle-plugin appends `:${osdetector.classifier}@exe` to the coordinate at resolution time.

---

## Usage

### Command line

Either download the binary for your platform from Maven Central (see classifiers above) or build it locally with GraalVM 21:

```bash
./gradlew :protoc-utils-recorder:nativeCompile
# binary is written to recorder/build/native/nativeCompile/protoc-utils-recorder-<os>-<arch>.exe
```

Then invoke it as a normal protoc plugin. The `--recorder_out` argument controls the directory where `code-generator-request.binpb` is written.

```bash
protoc \
  --plugin=protoc-gen-recorder=./protoc-utils-recorder-osx-aarch_64.exe \
  --recorder_out=./out \
  --proto_path=src/main/proto \
  src/main/proto/my/package/my_service.proto
```

`out/code-generator-request.binpb` now contains the exact bytes that `protoc` would have sent to your plugin.

---

### Gradle protobuf plugin

Reference the recorder as an `artifact` and the protobuf Gradle plugin will pull the right per-platform binary from Maven Central and copy it into the test resources:

```kotlin
// build.gradle.kts
plugins {
    id("com.google.protobuf")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.1"
    }
    plugins {
        create("recorder") {
            artifact = "com.engine:protoc-utils-recorder:<version>"
        }
    }
    generateProtoTasks {
        all().all {
            tasks.findByPath(":my-project:processTestResources")!!.dependsOn(this)
            plugins {
                create("recorder")
            }
        }
    }
}

tasks.named("processTestResources", ProcessResources::class) {
    from(
        layout.buildDirectory
            .dir("generated/source/proto/test/recorder")
            .map { it.file("code-generator-request.binpb") }
    )
}
```

After `./gradlew generateTestProto` (or a full `./gradlew test`), the file appears at `build/resources/test/code-generator-request.binpb` and is available via `getResourceAsStream("/code-generator-request.binpb")` in any test.

---

### Loading the fixture in a unit test

The `.binpb` is a serialized `CodeGeneratorRequest`. Parse it with whatever `ExtensionRegistry` your plugin uses, then wrap it with `protoc-utils` to drive assertions or invoke your plugin's compile logic.

```kotlin
import com.engine.protoc.util.extensions.wrap
import com.google.protobuf.ExtensionRegistry
import com.google.protobuf.compiler.PluginProtos

// build a registry that knows about every extension your .proto files use
val registry = ExtensionRegistry.newInstance().apply {
    MyExtensions.registerAllExtensions(this)
}

val cgreq = checkNotNull(javaClass.getResourceAsStream("/code-generator-request.binpb"))
    .use { PluginProtos.CodeGeneratorRequest.parseFrom(it, registry) }
    .wrap()                         // CodeGeneratorRequestWrapper

// navigate the descriptor tree just as your plugin would
val file = cgreq.sourceFileDescriptors
    .find { it.name == "my/package/my_service.proto" }!!

val service = file.services[0]
println(service.name?.value)        // e.g. "MyService"
println(service.methods[0].name?.value)
```

To invoke your plugin's compile logic end-to-end, pass the raw `CodeGeneratorRequest` proto directly to the entry point under test:

```kotlin
import com.google.protobuf.compiler.PluginProtos
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MyPluginTest : FunSpec({

    val registry = ExtensionRegistry.newInstance().apply {
        MyExtensions.registerAllExtensions(this)
    }

    val rawRequest = checkNotNull(javaClass.getResourceAsStream("/code-generator-request.binpb"))
        .use { PluginProtos.CodeGeneratorRequest.parseFrom(it, registry) }

    test("plugin produces expected output file names") {
        // pass the parsed request to your plugin's compile function
        val response = MyPlugin.compile(rawRequest)

        response.fileList.map { it.name } shouldBe listOf("my/package/my_service.openapi.yaml")
    }

    test("generated output contains expected content") {
        val response = MyPlugin.compile(rawRequest)
        val content = response.fileList.first { it.name.endsWith(".yaml") }.content

        content shouldContain "MyService"
    }
})
```

Because the fixture is committed to the repository, the tests run without any `protoc` invocation at test time: CI only needs a JVM, not a protoc installation.

> **Tip:** If you change your `.proto` files, re-run `./gradlew generateTestProto` (or `./gradlew test`, which triggers it automatically) to regenerate `code-generator-request.binpb` and commit the updated fixture.