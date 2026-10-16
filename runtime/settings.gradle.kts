pluginManagement {
    repositories {
        maven("https://maven.fabricmc.net/")
        gradlePluginPortal()
    }
}

// Downloads the JDKs the adapters compile for (8, 17, 21, 25) when they aren't installed.
plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

rootProject.name = "kite-runtime"
include("core", "mods",
    "v1_16_5", "v1_17_1", "v1_18_2", "v1_19", "v1_19_2", "v1_19_3", "v1_19_4", "v1_20", "v1_20_1", "v1_20_2", "v1_20_3", "v1_20_4", "v1_20_5", "v1_20_6",
    "v1_21", "v1_21_1", "v1_21_3", "v1_21_4", "v1_21_5", "v1_21_6", "v1_21_7", "v1_21_8", "v1_21_9", "v1_21_10", "v1_21_11",
    "v26_1", "v26_1_1", "v26_1_2", "v26_2", "v26_3")
