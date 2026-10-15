// Fabric adapter for Minecraft 1.19.2 (Mojang mappings). Common setup: ../adapter.gradle.kts.
plugins { id("net.fabricmc.fabric-loom-remap") version "1.18.2" }

extra["mc"] = "1.19.2"
extra["java"] = 17
extra["sharedSrc"] = listOf("fabric-common/src/main/java", "mc-shared/src/main/java")
apply(from = rootProject.file("adapter.gradle.kts"))

dependencies {
    minecraft("com.mojang:minecraft:1.19.2")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
}

loom {
    runs {
        named("client") { programArgs("--username", "AetherDev") }
        register("autotest") {
            inherit(getByName("client"))
            configName = "Aether Autotest"
            vmArg("-Daether.autotest=true")
        }
        register("audit") {
            inherit(getByName("client"))
            configName = "Aether Mixin Audit"
            vmArgs("-Daether.audit=true", "-Dmixin.debug.countInjections=true")
        }
    }
}
