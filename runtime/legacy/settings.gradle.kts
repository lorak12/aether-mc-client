// Legacy Fabric adapters (Minecraft 1.7.10, 1.8.9, 1.12.2). A separate build because Legacy Fabric's Gradle plugin
// (legacy-looming) must match its Loom version, which is older than the one the modern adapters in ../ use.
// Build with: cd runtime && ./gradlew -p legacy build  (core and mods are shared with the main build).
pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        maven("https://maven.legacyfabric.net/")
        gradlePluginPortal()
    }
}

// Downloads the JDKs the adapters compile for (8, 17, 21, 25) when they aren't installed.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "kite-legacy"
include("core", "mods")
project(":core").projectDir = file("../core")
project(":mods").projectDir = file("../mods")
include("v1_12_2", "v1_8_9", "v1_7_10")
