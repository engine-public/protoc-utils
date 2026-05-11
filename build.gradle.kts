import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.jreleaser.gradle.plugin.JReleaserExtension
import org.jreleaser.model.Active
import java.util.Calendar

plugins {
    alias(libs.plugins.graalvm.native).apply(false)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.osdetector)
    alias(libs.plugins.protobuf)
    `maven-publish`
}

fun calculateVersion(): String {
    return System
        .getenv("ENGINE_BUILD_VERSION")
        ?.let {
            it.ifEmpty {
                null
            }
        }
        ?: "0.0.0-pre.0" // temporary fallback version
}

val mavenStagingDir = layout.buildDirectory.dir("staging/maven-central")

configure<JReleaserExtension> {
    project {
        description = "Utilities to assist in the building of a protoc plugin."
        copyright = "Copyright ${Calendar.getInstance().get(Calendar.YEAR)} HotelEngine, Inc., d/b/a Engine"
        license = "Apache-2.0"
    }
    signing {
        active.set(Active.ALWAYS)
        armored.set(true)
    }
    deploy {
        maven {
            mavenCentral {
                create("sonatype") {
                    active.set(Active.ALWAYS)
                    url.set("https://central.sonatype.com/api/v1/publisher")
                    stagingRepository(mavenStagingDir.get().asFile.relativeTo(rootDir).path)
                }
            }
        }
    }
}

val jreleaserCreateBuildDir = tasks.register("jreleaserCreateBuildDir") {
    group = "publishing"
    doFirst { project.layout.buildDirectory.dir("jreleaser").get().asFile.mkdirs() }
}
tasks.named("jreleaserDeploy") {
    dependsOn(jreleaserCreateBuildDir)
}

val stageMavenCentral = tasks.register("stageMavenCentral") {
    group = "publishing"
}

allprojects {
    apply<IdeaPlugin>()
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "maven-publish")

    group = "com.engine"
    version = calculateVersion()

    repositories {
        mavenCentral()
    }

    configurations.named("ktlint").configure {
        resolutionStrategy {
            eachDependency {
                /*
                 * https://github.com/HotelEngine/protoc-gen-openapi/security/dependabot/3
                 * https://nvd.nist.gov/vuln/detail/CVE-2026-1225
                 */
                if (requested.group == "ch.qos.logback" && requested.module.name.startsWith("logback-")) {
                    useVersion("[1.5.25,)")
                }
            }
        }
    }

    configure<JavaPluginExtension> {
        withJavadocJar()
        withSourcesJar()
        toolchain {
            languageVersion.set(JavaLanguageVersion.of("21"))
            vendor.set(JvmVendorSpec.GRAAL_VM)
        }
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    configure<KotlinJvmProjectExtension> {
        explicitApi()
    }

    configure<KtlintExtension> {
        version.set("1.8.0")
        filter {
            /*
             * work around bug in the ktlint plugin that doesn't honor exclusions of
             * generated code (protobuf, etc.)
             */
            exclude {
                it.file.absolutePath.startsWith(layout.buildDirectory.get().asFile.absolutePath)
            }
        }
        reporters {
            reporter(ReporterType.CHECKSTYLE)
            reporter(ReporterType.HTML)
        }
    }

    tasks.withType<Jar>().configureEach {
        manifest {
            attributes(
                "Name" to project.name,
                "Specification-Title" to rootProject.name,
                "Specification-Version" to version,
                "Specification-Vendor" to "HotelEngine, Inc., d/b/a Engine",
            )
        }
    }

    afterEvaluate {
        configure<TestingExtension> {
            suites {
                configureEach {
                    if (this is JvmTestSuite) {
                        useJUnitJupiter()
                        dependencies {
                            implementation.bundle(libs.bundles.test.kotest)
                        }
                    }
                }
            }
        }

        tasks.withType<Test>().configureEach {
            jvmArgs("--add-opens=java.base/java.util=ALL-UNNAMED")
        }

        configure<PublishingExtension> {
            repositories {
                val mavenUser = System.getenv("MAVEN_USERNAME")
                val mavenPassword = System.getenv("MAVEN_PASSWORD")
                val mavenUrl = System.getenv("MAVEN_DEPLOY_URL")
                maven {
                    name = "stagingMaven"
                    url = mavenUrl?.let { uri(it) } ?: mavenStagingDir.get().asFile.toURI()
                    if (mavenUser != null) {
                        credentials {
                            username = mavenUser
                            password = mavenPassword
                        }
                    }
                }
            }
        }

        tasks.findByName("publish")?.also { publishTask ->
            stageMavenCentral.configure { dependsOn(publishTask) }
        }
    }
}

description = "Utilities to assist in the building of a protoc plugin."

dependencies {
    api(libs.protobuf.java)
    testImplementation(libs.protobuf.java)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            pom {
                name.set(project.name)
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
    /*
     * description isn't bound until subproject evaluation completes; set the
     * pom description here so it always lands in the published metadata.
     */
    publishing.publications.named<MavenPublication>("maven") {
        pom {
            description.set(project.description)
            url.set("https://github.com/hotelengine/protoc-gen-openapi/blob/${version}/README.md")
        }
    }
}

val processTestResources = tasks.named("processTestResources", ProcessResources::class) {
    from(project.layout.buildDirectory.dir("generated/sources/proto/test/recorder").map { it.file("code-generator-request.binpb") })
}

repositories {
    mavenLocal()
}

protobuf {
    protoc {
        artifact = libs.tools.protoc.compiler.get().toString()
    }
    plugins {
        create("recorder") {
            artifact = "com.engine:protoc-utils-recorder:0.0.0-pre.0"
        }
    }
    generateProtoTasks {
        all().all {
            if (isTest) {
                dependsOn(":protoc-utils-recorder:publishToMavenLocal")
                processTestResources.configure { dependsOn(this@all) }
                plugins {
                    create("recorder")
                }
            }
        }
    }
}

val writeVersion = tasks.register("writeVersion") {
    val versionFile = project.layout.buildDirectory.map { it.file("version.txt") }
    group = "build"
    outputs.file(versionFile)
    outputs.upToDateWhen {
        versionFile.get().asFile.exists() && versionFile.get().asFile.readText() == version.toString()
    }
    doFirst {
        versionFile
            .get()
            .asFile
            .apply { parentFile.mkdirs() }
            .writeText(version.toString())
    }
}

gradle.taskGraph.whenReady {
    gradle.taskGraph.allTasks.forEach {
        if (project.hasProperty("codeql")) {
            if (it.name.startsWith("nativeCompile")) {
                logger.quiet("Disabling ${it.path} due to codeql run.")
                it.enabled = false
            }
        }
    }
}
