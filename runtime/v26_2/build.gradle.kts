// Fabric adapter for Minecraft 26.2 (unobfuscated, so no remapping). Common setup: ../adapter.gradle.kts.
plugins { id("net.fabricmc.fabric-loom") version "1.18.2" }

extra["mc"] = "26.2"
extra["java"] = 25
extra["sharedSrc"] = listOf("fabric-common/src/main/java", "mc-shared/src/main/java")
apply(from = rootProject.file("adapter.gradle.kts"))

dependencies {
    minecraft("com.mojang:minecraft:26.2")
    implementation("net.fabricmc:fabric-loader:0.19.5")
}

loom {
    runs {
        // Config and worlds live in runtime/v26_2/run; the in-game menu opens with Right Shift.
        named("client") { programArgs("--username", "KiteDev") }
        // In-game smoke test: joins run/saves/KiteTest, screenshots every feature, quits. See DevAutotest.
        register("autotest") {
            inherit(getByName("client"))
            configName = "Kite Autotest"
            programArgs("--quickPlaySingleplayer", "KiteTest")
            vmArg("-Dkite.autotest=true")
        }
        // Mixin audit: loads every mixin target at the title screen and quits; a broken hook fails the run.
        register("audit") {
            inherit(getByName("client"))
            configName = "Kite Mixin Audit"
            vmArgs("-Dkite.audit=true", "-Dmixin.debug.countInjections=true")
        }
    }
}
