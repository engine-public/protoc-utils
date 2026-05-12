import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.filter.LicenseBundleNormalizer
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType
import org.jreleaser.gradle.plugin.JReleaserExtension
import org.jreleaser.model.Active
import org.jreleaser.sdk.tool.Cyclonedx
import java.util.Calendar

buildscript {
    configurations.classpath {
        resolutionStrategy.eachDependency {
            /*
             * https://github.com/hotelengine/protoc-utils/security/dependabot/2
             * GHSA-f58c-gq56-vjjf — Apache Tika XXE. Transitive of JReleaser.
             */
            if (requested.group == "org.apache.tika" && requested.name == "tika-core") {
                useVersion("3.2.2")
                because("Dependabot alert #2: Apache Tika XXE (GHSA-f58c-gq56-vjjf)")
            }
            /*
             * https://github.com/hotelengine/protoc-utils/security/dependabot/4
             * GHSA-6fmv-xxpf-w3cw — plexus-utils path traversal. Transitive of JReleaser.
             */
            if (requested.group == "org.codehaus.plexus" && requested.name == "plexus-utils") {
                useVersion("3.6.1")
                because("Dependabot alert #4: plexus-utils directory traversal (GHSA-6fmv-xxpf-w3cw)")
            }
        }
    }
}

plugins {
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.graalvm.native).apply(false)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.license.report).apply(false)
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

val licenseAllowlistFile = rootProject.file("gradle/license/allowed-licenses.json")

allprojects {
    apply<IdeaPlugin>()
    apply(plugin = "com.github.jk1.dependency-license-report")
    apply(plugin = "org.cyclonedx.bom")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jlleitschuh.gradle.ktlint")
    apply(plugin = "maven-publish")

    configure<LicenseReportExtension> {
        allowedLicensesFile = licenseAllowlistFile
        filters = arrayOf(LicenseBundleNormalizer())
        /*
         * Audit only what we actually ship. Test, ktlint, and build-tool
         * classpaths can pull in licenses we don't redistribute.
         */
        configurations = arrayOf("runtimeClasspath")
    }

    afterEvaluate {
        tasks.named("check") {
            dependsOn("checkLicense")
        }
    }

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
                 * GHSA-qqpg-mvqg-649v - Logback allows an attacker to instantiate classes already present on the class path - transitive of ktlint
                 * https://nvd.nist.gov/vuln/detail/CVE-2026-1225
                 */
                if (requested.group == "ch.qos.logback" && requested.module.name.startsWith("logback-")) {
                    useVersion("[1.5.25,)")
                    because("Dependabot alert 3 (from parent repo): Logback allows an attacker to instantiate classes already present on the class path")
                }
            }
        }
    }

    configure<JavaPluginExtension> {
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

    tasks.withType<CyclonedxDirectTask>().configureEach {
        includeConfigs = listOf("runtimeClasspath")
    }

    afterEvaluate {
        /*
         * Wire the direct BOM into `assemble` so `./gradlew build` produces a
         * fresh `build/reports/cyclonedx-direct/bom.json` for every module.
         * The maven publications below attach that file as a classified
         * artifact (classifier=cyclonedx, extension=json), so publish tasks
         * also trigger it transitively via `builtBy`.
         */
        tasks.named("assemble") {
            dependsOn(tasks.named("cyclonedxDirectBom"))
        }

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

// Only the library jar (this root project) ships sources + javadoc on Maven
// Central; the recorder is a POM-only native-binary distribution.
java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    api(libs.protobuf.java)
    testImplementation(libs.protobuf.java)
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
            artifact(layout.buildDirectory.file("reports/cyclonedx-direct/bom.json")) {
                classifier = "cyclonedx"
                extension = "json"
                builtBy(tasks.named("cyclonedxDirectBom"))
            }
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
    /*
     * The recorder native binary is consumed from Maven coordinates so the
     * published artifact name and POM are exercised by the test build itself.
     * Scoping mavenLocal to just the recorder module keeps every other
     * dependency (protobuf-java, kotlin-stdlib, …) resolving from
     * mavenCentral and avoids any local ~/.m2 staleness leaking into the
     * build.
     */
    mavenLocal {
        content {
            includeModule(group.toString(), projects.protocUtilsRecorder.name)
        }
    }
}

protobuf {
    protoc {
        artifact = libs.tools.protoc.compiler.get().toString()
    }
    plugins {
        create("recorder") {
            artifact = "$group:${projects.protocUtilsRecorder.name}:${version}"
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
