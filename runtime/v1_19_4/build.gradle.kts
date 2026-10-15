// Fabric adapter for Minecraft 1.19.4 (Mojang mappings). Common setup: ../adapter.gradle.kts.
plugins { id("net.fabricmc.fabric-loom-remap") version "1.18.2" }

extra["mc"] = "1.19.4"
extra["java"] = 17
extra["sharedSrc"] = listOf("fabric-common/src/main/java", "mc-shared/src/main/java")
apply(from = rootProject.file("adapter.gradle.kts"))

dependencies {
    minecraft("com.mojang:minecraft:1.19.4")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
}

loom {
    runs {
        // Config and worlds live in runtime/v1_19_4/run; the in-game menu opens with Right Shift.
        named("client") { programArgs("--username", "AetherDev") }
        // In-game smoke test: joins run/saves/AetherTest, screenshots every feature, quits. See DevAutotest.
        register("autotest") {
            inherit(getByName("client"))
            configName = "Aether Autotest"
            programArgs("--quickPlaySingleplayer", "AetherTest")
            vmArg("-Daether.autotest=true")
        }
        // Mixin audit: loads every mixin target at the title screen and quits; a broken hook fails the run.
        register("audit") {
            inherit(getByName("client"))
            configName = "Aether Mixin Audit"
            vmArgs("-Daether.audit=true", "-Dmixin.debug.countInjections=true")
        }
    }
}
