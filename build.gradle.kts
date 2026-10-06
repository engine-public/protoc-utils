import com.github.jk1.license.LicenseReportExtension
import com.github.jk1.license.filter.LicenseBundleNormalizer
import org.cyclonedx.gradle.CyclonedxDirectTask
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import org.jlleitschuh.gradle.ktlint.KtlintExtension
import org.jlleitschuh.gradle.ktlint.reporter.ReporterType

buildscript {
    configurations.classpath {
        resolutionStrategy.eachDependency {
            /*
             * https://github.com/engine-public/protoc-utils/security/dependabot/9
             *  … through https://github.com/engine-public/protoc-utils/security/dependabot/15
             *  and https://github.com/engine-public/protoc-utils/security/dependabot/18
             * Eight jackson-databind advisories — PolymorphicTypeValidator bypasses
             * (CVE-2026-54513, CVE-2026-54512), @JsonView / @JsonIgnore /
             * @JsonIgnoreProperties bypasses (CVE-2026-54517, CVE-2026-54516,
             * CVE-2026-54515, CVE-2026-54518), InetSocketAddress eager-DNS SSRF
             * (CVE-2026-54514), and a further @JsonView bypass for @JsonUnwrapped
             * container properties (GHSA-5gvw-p9qm-jgwh, first patched in 2.22.1).
             *
             * https://github.com/engine-public/protoc-utils/security/dependabot/22
             *  … through https://github.com/engine-public/protoc-utils/security/dependabot/29
             * Eight further jackson-databind/-core advisories, all first patched by
             * 2.22.3 — incomplete InetAddress eager-DNS SSRF fix (GHSA-vvgp-rfg2-7rr6),
             * Path deserialization FileSystemProvider scheme allowlist
             * (GHSA-wjgm-6hv5-3cvf), Duration/XMLGregorianCalendar number-parse DoS
             * (GHSA-q4xh-88c3-wmh7), Comparable missing from the PTV unsafe base types
             * (GHSA-gx83-3vf8-gh7j), unbounded retention of unknown type IDs
             * (GHSA-wv8q-qhhj-9h54), quadratic forward-reference completion
             * (GHSA-cxp5-3px4-pw24), NumberInput.PATTERN_FLOAT ReDoS
             * (GHSA-p6pp-m3f8-5c89), and unbounded _reportInvalidToken growth
             * (GHSA-7hhh-6rmp-j9qf).
             *
             * Transitive of the CycloneDX plugin.
             * jackson-core is bumped in lock-step to avoid databind/core skew;
             * jackson-annotations tracks its own 2.22 line via the BOM.
             */
            if (requested.group == "com.fasterxml.jackson.core" &&
                (requested.name == "jackson-databind" || requested.name == "jackson-core")
            ) {
                useVersion("2.22.3")
                because("Dependabot alerts #9-#15,#18,#22-#29: jackson-databind/-core PTV/@JsonView/@JsonIgnore/@JsonUnwrapped bypasses, SSRF, and DoS (CVE-2026-54512…54518, GHSA-5gvw-p9qm-jgwh, GHSA-vvgp-rfg2-7rr6 et al.)")
            }
        }
    }
}

plugins {
    alias(libs.plugins.cyclonedx)
    alias(libs.plugins.graalvm.native).apply(false)
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
                maven {
                    name = "GitHubPackages"
                    url = uri("https://maven.pkg.github.com/engine-public/protoc-utils")
                    credentials {
                        username = System.getenv("GITHUB_ACTOR")
                        password = System.getenv("GITHUB_TOKEN")
                    }
                }
            }
        }
    }
}

description = "Utilities to assist in the building of a protoc plugin."

// Only the library jar (this root project) ships sources + javadoc; the
// recorder is a POM-only native-binary distribution.
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
    /*
     * description isn't bound until subproject evaluation completes; set the
     * pom description here so it always lands in the published metadata.
     */
    publishing.publications.named<MavenPublication>("maven") {
        pom {
            description.set(project.description)
            url.set("https://github.com/engine-public/protoc-utils/blob/${version}/README.md")
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
