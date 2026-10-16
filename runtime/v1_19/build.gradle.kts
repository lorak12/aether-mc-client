// Fabric adapter for Minecraft 1.19 (Mojang mappings). Common setup: ../adapter.gradle.kts.
plugins { id("net.fabricmc.fabric-loom-remap") version "1.18.2" }

extra["mc"] = "1.19"
extra["java"] = 17
extra["sharedSrc"] = listOf("fabric-common/src/main/java", "mc-shared/src/main/java")
apply(from = rootProject.file("adapter.gradle.kts"))

dependencies {
    minecraft("com.mojang:minecraft:1.19")
    mappings(loom.officialMojangMappings())
    modImplementation("net.fabricmc:fabric-loader:0.19.5")
}

loom {
    runs {
        named("client") { programArgs("--username", "KiteDev") }
        register("autotest") {
            inherit(getByName("client"))
            configName = "Kite Autotest"
            vmArg("-Dkite.autotest=true")
        }
        register("audit") {
            inherit(getByName("client"))
            configName = "Kite Mixin Audit"
            vmArgs("-Dkite.audit=true", "-Dmixin.debug.countInjections=true")
        }
    }
}
