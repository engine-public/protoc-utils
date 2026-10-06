plugins {
    application
    `maven-publish`
    alias(libs.plugins.osdetector)
    alias(libs.plugins.graalvm.native)
}

dependencies {
    implementation(libs.protobuf.java)
}

application {
    mainClass = "com.engine.protoc.util.recorder.MainKt"
}

graalvmNative {
    toolchainDetection = false
    binaries {
        named("main") {
            // GraalVM auto-appends .exe on Windows; everywhere else we add it
            // explicitly so every published native artifact ends in .exe (the
            // io.grpc:protoc-gen-grpc-java convention).
            val exeSuffix = if (osdetector.os == "windows") "" else ".exe"
            imageName = "${project.name}-${osdetector.os}-${osdetector.arch}$exeSuffix"
            mainClass = application.mainClass
            sharedLibrary = false
            resources.autodetect()
            fallback = false
        }
        all {
            verbose = true
            javaLauncher.set(javaToolchains.launcherFor {
                languageVersion.set(JavaLanguageVersion.of(21))
                vendor.set(JvmVendorSpec.GRAAL_VM)
            })
            buildArgs.add("-H:+UnlockExperimentalVMOptions")
            buildArgs.add("-H:ThrowMissingRegistrationErrors=")
        }
    }
}

description = "A protoc plugin that records the CodeGeneratorRequest sent by protoc as a binary fixture."

/*
 * Per-platform native binaries are published as classified artifacts on a
 * POM-only artifact (no main jar, mirroring io.grpc:protoc-gen-grpc-java).
 * Every binary uses the .exe extension regardless of host OS, so the artifact
 * coordinates can be resolved with `:<classifier>@exe` on every platform.
 */
val classifiedNativeArtifacts = listOf(
    "linux-x86_64",
    "linux-aarch_64",
    "osx-aarch_64",
    "windows-x86_64",
)

val nativeBinariesDir: Provider<File> = providers
    .environmentVariable("ENGINE_NATIVE_BIN_DIR")
    .map { rootProject.layout.projectDirectory.dir(it).asFile }
    .orElse(layout.buildDirectory.dir("native/nativeCompile").map { it.asFile })

publishing {
    publications {
        create<MavenPublication>("maven") {
            // intentionally no `from(components["java"])` — recorder ships
            // only the classified native binaries below, and the main pom is
            // <packaging>pom</packaging>.
            artifact(layout.buildDirectory.file("reports/cyclonedx-direct/bom.json")) {
                classifier = "cyclonedx"
                extension = "json"
                builtBy(tasks.named("cyclonedxDirectBom"))
            }
            pom {
                name.set(project.name)
                packaging = "pom"
                inceptionYear.set("2025")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://github.com/engine-public/protoc-utils/blob/${version}/LICENSE")
                    }
                }
                developers {
                    developer {
                        organizationUrl.set("https://github.com/engine-public")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/engine-public/protoc-utils.git")
                    developerConnection.set("scm:git:https://github.com/engine-public/protoc-utils.git")
                    url.set("https://github.com/engine-public/protoc-utils")
                }
            }
        }
    }
}

afterEvaluate {
    val pub = publishing.publications.getByName<MavenPublication>("maven")
    pub.pom {
        description.set(project.description)
        url.set("https://github.com/engine-public/protoc-utils/blob/${version}/recorder/README.md")
    }

    val binDir = nativeBinariesDir.get()
    val localClassifier = "${osdetector.os}-${osdetector.arch}"
    val nativeCompileTask = tasks.named("nativeCompile")
    val localBinary = layout.buildDirectory.file(
        "native/nativeCompile/${project.name}-$localClassifier.exe",
    )

    classifiedNativeArtifacts.forEach { classifier ->
        // CI release staging produces version-tagged file names; prefer that
        // form when present so all classifiers attach to the publication.
        val stagedFile = binDir.resolve("${project.name}-${project.version}-$classifier.exe")
        when {
            stagedFile.exists() -> pub.artifact(stagedFile) {
                this.classifier = classifier
                this.extension = "exe"
            }
            classifier == localClassifier -> pub.artifact(localBinary) {
                this.classifier = classifier
                this.extension = "exe"
                // Build the binary on demand so publishToMavenLocal triggers
                // nativeCompile automatically.
                builtBy(nativeCompileTask)
            }
            else -> logger.info(
                "No native binary for classifier '{}'; skipping artifact.",
                classifier,
            )
        }
    }
}
