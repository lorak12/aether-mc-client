// Legacy Fabric adapter for Minecraft 1.8.9 (Legacy Yarn mappings). Common setup: ../../adapter.gradle.kts.
plugins {
    id("net.fabricmc.fabric-loom-remap") version "1.16.1"
    id("legacy-looming") version "1.16.1" // must match the Loom version
}

extra["mc"] = "1.8.9"
extra["java"] = 8
extra["mappings"] = "yarn"
extra["sharedSrc"] = listOf("fabric-common/src/main/java", "legacy/shared/src/main/java")
apply(from = "../../adapter.gradle.kts")

dependencies {
    minecraft("com.mojang:minecraft:1.8.9")
    mappings(legacy.yarn("1.8.9", "604"))
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
}

loom {
    runs {
        // Config and worlds live in runtime/legacy/v1_8_9/run; the in-game menu opens with Right Shift.
        named("client") { programArgs("--username", "KiteDev") }
        // Mixin audit: loads every mixin target at the title screen and quits; a broken hook fails the run.
        register("audit") {
            inherit(getByName("client"))
            configName = "Kite Mixin Audit"
            vmArgs("-Dkite.audit=true", "-Dmixin.debug.countInjections=true")
        }
    }
}
