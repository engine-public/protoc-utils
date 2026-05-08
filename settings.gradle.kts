rootProject.name = "protoc-utils"

pluginManagement {
    repositories {
        gradlePluginPortal()
    }
}

rootDir
    .walkTopDown()
    .onEnter { dir ->
        dir == rootDir || (
            !dir.name.startsWith(".") &&
                dir.name !in setOf("build", "buildSrc", "tmp", "scratch") &&
                !dir.resolve(".gradle_ignore").exists()
            )
    }
    .filter { it != rootDir && it.isDirectory }
    .filter { it.resolve("build.gradle.kts").run { exists() && isFile } }
    .forEach {
        val relativePath = it.relativeTo(rootDir)
        val projectName = ":${rootProject.name}-${relativePath.path.replace("/", "-")}"
        include(projectName)
        project(projectName).projectDir = it
    }

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
