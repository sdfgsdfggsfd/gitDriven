plugins {
    // Downloads the toolchain JDK automatically if the machine doesn't have it.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "gitDriven"

// Every folder in plugins/ that has a build.gradle.kts becomes a plugin automatically.
// No need to edit this file to add a new plugin.
file("plugins").listFiles()
    ?.filter { it.isDirectory && it.resolve("build.gradle.kts").isFile }
    ?.sortedBy { it.name }
    ?.forEach { include(":plugins:${it.name}") }
