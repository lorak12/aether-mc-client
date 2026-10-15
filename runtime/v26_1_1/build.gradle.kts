// Fabric adapter for Minecraft 26.1.1 (unobfuscated). Common setup: ../adapter.gradle.kts.
plugins { id("net.fabricmc.fabric-loom") version "1.18.2" }

extra["mc"] = "26.1.1"
extra["java"] = 25
extra["sharedSrc"] = listOf("fabric-common/src/main/java", "mc-shared/src/main/java")
apply(from = rootProject.file("adapter.gradle.kts"))

dependencies {
    minecraft("com.mojang:minecraft:26.1.1")
    implementation("net.fabricmc:fabric-loader:0.19.5")
}

loom {
    runs {
        named("client") { programArgs("--username", "AetherDev") }
        register("autotest") {
            inherit(getByName("client"))
            configName = "Aether Autotest"
            programArgs("--quickPlaySingleplayer", "AetherTest")
            vmArg("-Daether.autotest=true")
        }
        register("audit") {
            inherit(getByName("client"))
            configName = "Aether Mixin Audit"
            vmArgs("-Daether.audit=true", "-Dmixin.debug.countInjections=true")
        }
    }
}
