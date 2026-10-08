description = "CommonMark helpers for protoc plugins: proto reference-link resolution and plain-text scrubbing of doc comments."

java {
    withJavadocJar()
    withSourcesJar()
}

dependencies {
    api(rootProject)
    api(libs.commonmark)
    implementation(libs.slf4j.api)
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
                inceptionYear.set("2026")
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
    publishing.publications.named<MavenPublication>("maven") {
        pom {
            description.set(project.description)
            url.set("https://github.com/engine-public/protoc-utils/blob/${version}/markdown/README.md")
        }
    }
}
