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
            imageName = "${project.name}-${osdetector.os}-${osdetector.arch}"
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
 * Per-platform native binaries are published to Maven Central as classified
 * artifacts on a POM-only artifact (no main jar, mirroring io.grpc:protoc-gen-grpc-java).
 * Only the windows binary uses the .exe extension; linux and osx artifacts are
 * extension-less.
 */
val classifiedNativeArtifacts = listOf(
    "linux-x86_64" to "",
    "linux-aarch_64" to "",
    "osx-aarch_64" to "",
    "windows-x86_64" to "exe",
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
            pom {
                name.set(project.name)
                packaging = "pom"
                inceptionYear.set("2025")
                licenses {
                    license {
                        name.set("Apache-2.0")
                        url.set("https://github.com/hotelengine/protoc-gen-openapi/blob/${version}/LICENSE")
                    }
                }
                developers {
                    developer {
                        organizationUrl.set("https://github.com/hotelengine")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/hotelengine/protoc-gen-openapi.git")
                    developerConnection.set("scm:git:https://github.com/hotelengine/protoc-gen-openapi.git")
                    url.set("https://github.com/hotelengine/protoc-gen-openapi")
                }
            }
        }
    }
}

afterEvaluate {
    val pub = publishing.publications.getByName<MavenPublication>("maven")
    pub.pom {
        description.set(project.description)
        url.set("https://github.com/hotelengine/protoc-gen-openapi/blob/${version}/recorder/README.md")
    }

    val binDir = nativeBinariesDir.get()
    classifiedNativeArtifacts.forEach { (classifier, ext) ->
        val suffix = if (ext.isEmpty()) "" else ".$ext"
        // Look for either the local nativeCompile output (no version embedded
        // in the file name) or a release-staged file (version-tagged).
        val candidates = listOf(
            "${project.name}-$classifier$suffix",
            "${project.name}-${project.version}-$classifier$suffix",
        )
        val artifactFile = candidates
            .map { binDir.resolve(it) }
            .firstOrNull { it.exists() }
        if (artifactFile != null) {
            pub.artifact(artifactFile) {
                this.classifier = classifier
                this.extension = ext
            }
        } else {
            logger.info(
                "No native binary found for classifier '{}' in {}; skipping artifact.",
                classifier,
                binDir,
            )
        }
    }
}
