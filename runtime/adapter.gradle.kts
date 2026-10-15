// Shared by every Fabric adapter (applied from each vX/build.gradle.kts after its Loom plugin and minecraft
// dependency). The adapter sets `extra["mc"]`, `extra["java"]` and `extra["sharedSrc"]` before applying this.
// Shades core + mods into one self-contained jar: no Fabric API needed, so dropping build/libs/aether-*.jar into a
// Fabric profile's mods folder is enough.

val mc = extra["mc"] as String
// runtime/: this script's folder. Shared source dirs are relative to it, so the Legacy Fabric build in runtime/legacy
// (its own Gradle build) applies the same script.
val runtimeDir: File = buildscript.sourceFile!!.parentFile
val javaVersion = extra["java"] as Int
@Suppress("UNCHECKED_CAST")
val sharedSrc = extra["sharedSrc"] as List<String>

version = "0.1.0"
extensions.configure<BasePluginExtension> { archivesName.set("aether-$mc") }

// Java 8 targets compile with JDK 17 and --release 8 (JDK 8's javac has no --release); dev runs use it too, which
// Legacy Fabric and 1.16.5 handle fine.
extensions.configure<JavaPluginExtension> { toolchain.languageVersion.set(JavaLanguageVersion.of(maxOf(javaVersion, 17))) }
// -Xmaxerrs: javac stops at 100 errors by default, which hides the rest of a port's breakage.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(javaVersion)
    options.compilerArgs.addAll(listOf("-Xmaxerrs", "2000"))
}

// Adapter code shared by several Minecraft versions (gg.aether.mc and friends), compiled against this version; see
// Compat. Layered: a file in this adapter's own src/main/java replaces the shared file with the same path, and among
// the shared dirs the first listed wins. Fonts and textures shared by every version live in mc-shared's resources.
//
// Shared files go through a small preprocessor (see ../PREPROCESSOR.md):
//   //#if >=1.21.11 && <26.1   ...   //#elif <1.20.5   ...   //#else   ...   //#endif
// keeps only the branch for this version, and `renames` turns newer Mojang names into this version's (whole words).
val ownSrc = file("src/main/java")

fun versionParts(v: String): List<Int> = v.split('.').map { it.takeWhile(Char::isDigit).toIntOrNull() ?: 0 }
fun compareVersions(a: String, b: String): Int {
    val x = versionParts(a)
    val y = versionParts(b)
    for (i in 0 until maxOf(x.size, y.size)) {
        val c = (x.getOrElse(i) { 0 }).compareTo(y.getOrElse(i) { 0 })
        if (c != 0) return c
    }
    return 0
}
fun versionMatches(cond: String): Boolean = cond.split("&&").map(String::trim).filter(String::isNotEmpty).all { term ->
    val op = listOf(">=", "<=", "==", "!=", ">", "<").first { term.startsWith(it) }
    val c = compareVersions(mc, term.removePrefix(op).trim())
    when (op) { ">=" -> c >= 0; "<=" -> c <= 0; "==" -> c == 0; "!=" -> c != 0; ">" -> c > 0; else -> c < 0 }
}

// Newest name -> this version's name, applied when this version is older than the version that introduced the name.
// Listed newest change first; each entry is (introduced in, newer name, older name). Whole-word matches only.
val renameTable: List<Triple<String, String, String>> = listOf(
    Triple("1.21.6", "getCurrentVersion().name()", "getCurrentVersion().getName()"),
    Triple("1.21.5", "PoseStack;mulPose(Lorg/joml/Quaternionfc;)V", "PoseStack;mulPose(Lorg/joml/Quaternionf;)V"),
    // 1.21.4: render states call worn armor "equipment"; Equippable's model became assetId.
    Triple("1.21.4", "headEquipment", "headItem"),
    Triple("1.21.4", "chestEquipment", "chestItem"),
    Triple("1.21.4", "legsEquipment", "legsItem"),
    Triple("1.21.4", "feetEquipment", "feetItem"),
    Triple("1.21.4", "assetId()", "model()"),
    // authlib 7 (1.21.9): GameProfile became a record.
    Triple("1.21.9", "getProfile().name()", "getProfile().getName()"),
    Triple("1.21.9", "getProfile().id()", "getProfile().getId()"),
    Triple("1.21.9", "profile().properties()", "profile().getProperties()"),
    Triple("1.21.9", "services().sessionService()", "getMinecraftSessionService()"),
    // 1.21.9: players render through Avatar* types and submit nodes instead of buffers.
    Triple("1.21.9", "net.minecraft.world.entity.Avatar", "net.minecraft.client.player.AbstractClientPlayer"),
    Triple("1.21.9", "Lnet/minecraft/world/entity/Avatar;", "Lnet/minecraft/client/player/AbstractClientPlayer;"),
    Triple("1.21.9", "Avatar", "AbstractClientPlayer"),
    Triple("1.21.9", "AvatarRenderState", "PlayerRenderState"),
    Triple("1.21.9", "AvatarRenderer", "PlayerRenderer"),
    Triple("1.21.9", "SubmitNodeCollector", "MultiBufferSource"),
    Triple("1.21.9", "net.minecraft.world.entity.player.PlayerSkin", "net.minecraft.client.resources.PlayerSkin"),
    Triple("1.21.11", "Identifier", "ResourceLocation"),
    Triple("1.21.11", "identifier", "location"),
    Triple("1.21.11", "net.minecraft.util.Util", "net.minecraft.Util"),
    Triple("1.21.11", "model.player.PlayerModel", "model.PlayerModel"),
    Triple("1.21.11", "rendertype.RenderTypes", "RenderType"),
    Triple("1.21.11", "rendertype.RenderType", "RenderType"),
    Triple("1.21.11", "RenderTypes", "RenderType"),
    Triple("1.21.11", "refreshCategoryVolume", "updateSourceVolume"),
    // 1.21.2: entity render states, ToastManager, ShapeRenderer, Level.getMinY/getMaxY, ARGB NativeImage pixels.
    // Older versions render players from the entity; gg.aether.mc.PlayerState stands in for the render state.
    Triple("1.21.2", "net.minecraft.client.renderer.entity.state.PlayerRenderState", "gg.aether.mc.PlayerState"),
    Triple("1.21.2", "PlayerRenderState", "PlayerState"),
    Triple("1.21.2", "toasts.ToastManager", "toasts.ToastComponent"),
    Triple("1.21.2", "ToastManager", "ToastComponent"),
    Triple("1.21.2", "ShapeRenderer", "LevelRenderer"),
    Triple("1.21.2", "level().getMinY()", "level().getMinBuildHeight()"),
    Triple("1.21.2", "lv.getMinY()", "lv.getMinBuildHeight()"),
    Triple("1.21.2", "level().getMaxY()", "level().getMaxBuildHeight() - 1"),
    Triple("1.21.2", "registryAccess().lookupOrThrow", "registryAccess().registryOrThrow"),
    Triple("1.21.2", "p.gameTime()", "p.getGameTime()"),
    Triple("1.21.2", "getPixel(", "getPixelRGBA("),
    Triple("1.21.2", "setPixel(", "setPixelRGBA("),
    // 1.21: DeltaTracker, ResourceLocation factories, Holder-based enchantment names, options screens package.
    Triple("1.21", "import net.minecraft.client.DeltaTracker;", ""),
    Triple("1.21", "DeltaTracker dt", "float dt"),
    Triple("1.21", "ResourceLocation.fromNamespaceAndPath(", "new ResourceLocation("),
    Triple("1.21", "Enchantment.getFullname(h, e.getLevel(h))", "h.value().getFullname(e.getLevel(h.value()))"),
    Triple("1.21", "screens.options.OptionsScreen", "screens.OptionsScreen"),
    // 1.20.5: the chat's trimmed-line refresh was singular before.
    Triple("1.20.5", "refreshTrimmedMessages", "refreshTrimmedMessage"),
    // 1.20.3: Util's IO pool and authlib 6's fetchProperties.
    Triple("1.20.3", "nonCriticalIoPool()", "backgroundExecutor()"),
    Triple("1.20.3", "api.fetchProperties()", "api.properties()"),
    // 1.20.2: DisplaySlot, AdvancementHolder, the debug screen object, section stats, ClientPacketListener.serverBrand.
    Triple("1.20.2", "import net.minecraft.world.scores.DisplaySlot;", ""),
    Triple("1.20.2", "getDisplayObjective(DisplaySlot.SIDEBAR)", "getDisplayObjective(net.minecraft.world.scores.Scoreboard.DISPLAY_SLOT_SIDEBAR)"),
    Triple("1.20.2", "!e.getKey().value().display().isPresent()", "e.getKey().getDisplay() == null"),
    Triple("1.20.2", "advancements.AdvancementHolder", "advancements.Advancement"),
    Triple("1.20.2", "AdvancementHolder", "Advancement"),
    Triple("1.20.2", "mc.getDebugOverlay().showDebugScreen()", "mc.options.renderDebug"),
    Triple("1.20.2", "getSectionStatistics()", "getChunkStatistics()"),
    Triple("1.20.2", "c.serverBrand()", "(mc.player == null ? null : mc.player.getServerBrand())"),
    Triple("1.20.2", "mc.disconnect();", "mc.clearLevel();"),
    // The built-in registry holder moved from Registry to BuiltInRegistries in 1.19.3.
    Triple("1.19.3", "net.minecraft.core.registries.BuiltInRegistries", "net.minecraft.core.Registry"),
    Triple("1.19.3", "BuiltInRegistries", "Registry"),
    // 1.16 has no slf4j: log4j's logger takes the same {} placeholders.
    Triple("1.17", "org.slf4j.LoggerFactory", "org.apache.logging.log4j.LogManager"),
    Triple("1.17", "import org.slf4j.Logger;", "import org.apache.logging.log4j.Logger;"),
    Triple("1.17", "org.slf4j.Logger", "org.apache.logging.log4j.Logger"),
    Triple("1.17", "LoggerFactory", "LogManager"),
    // 1.16: entity rotation, inventory and abilities are fields.
    Triple("1.17", "getYRot()", "yRot"),
    Triple("1.17", "getXRot()", "xRot"),
    Triple("1.17", "getAbilities()", "abilities"),
    Triple("1.17", "getInventory()", "inventory"),
    Triple("1.17", "getEyePosition()", "getEyePosition(1.0F)"),
    Triple("1.17", "e.getProgress()", "e.getPercent()"),
    Triple("1.17", "isCurrentlyGlowing", "isGlowing"),
    Triple("1.17", "net.minecraft.world.scores.Scoreboard.DISPLAY_SLOT_SIDEBAR", "1"),
    // 1.19 moved to Component.literal / empty and RandomSource; before, TextComponent and java.util.Random.
    Triple("1.19", "net.minecraft.network.chat.Component.literal(", "new net.minecraft.network.chat.TextComponent("),
    Triple("1.19", "Component.literal(", "new net.minecraft.network.chat.TextComponent("),
    Triple("1.19", "net.minecraft.network.chat.Component.empty()", "new net.minecraft.network.chat.TextComponent(\"\")"),
    Triple("1.19", "Component.empty()", "new net.minecraft.network.chat.TextComponent(\"\")"),
    Triple("1.19", "import net.minecraft.util.RandomSource;", ""),
    Triple("1.19", "RandomSource", "java.util.Random"),
    // 1.19: the chat has no refreshTrimmedMessage; rescaleChat rebuilds the wrapped lines.
    Triple("1.19.1", "refreshTrimmedMessage", "rescaleChat"),
)
// The table is in Mojang names; Yarn-mapped builds (Legacy Fabric) set extra["mappings"] = "yarn" and use their own
// table: Legacy Yarn 1.7.10 leaves a few members unnamed (the player field), spelled the same way everywhere here.
val mojangNames = (if (extra.has("mappings")) extra["mappings"] else "mojang") == "mojang"
val yarnRenameTable: List<Triple<String, String, String>> = listOf(
    Triple("1.8", "mc.player", "mc.field_3805"),
    Triple("1.8", "client.player", "client.field_3805"),
    Triple("1.8", "MinecraftClient.getInstance().player", "MinecraftClient.getInstance().field_3805"),
)
val renames: List<Pair<Regex, String>> = (if (mojangNames) renameTable else yarnRenameTable).filter { compareVersions(mc, it.first) < 0 }.map {
    val from = it.second
    // Word boundaries only where the name starts / ends with a word character ("getProfile().id()" ends in ')').
    fun word(c: Char) = c.isLetterOrDigit() || c == '_'
    Regex((if (word(from.first())) "\\b" else "") + Regex.escape(from) + (if (word(from.last())) "\\b" else "")) to it.third
}

/** One preprocessor line. `stack` holds the open blocks of this file: [branch active, a branch taken, parent active]. */
fun preprocessLine(line: String, stack: ArrayDeque<BooleanArray>, where: String): String {
    val t = line.trim()
    fun active() = stack.lastOrNull()?.get(0) ?: true
    when {
        t.startsWith("//#if ") -> {
            val parent = active()
            val on = parent && versionMatches(t.removePrefix("//#if "))
            stack.addLast(booleanArrayOf(on, on, parent))
        }
        t.startsWith("//#elif ") -> {
            val b = stack.lastOrNull() ?: error("$where: //#elif without //#if")
            val on = b[2] && !b[1] && versionMatches(t.removePrefix("//#elif "))
            b[0] = on
            b[1] = b[1] || on
        }
        t == "//#else" -> {
            val b = stack.lastOrNull() ?: error("$where: //#else without //#if")
            b[0] = b[2] && !b[1]
            b[1] = true
        }
        t == "//#endif" -> stack.removeLastOrNull() ?: error("$where: //#endif without //#if")
        // Dropped lines become blank so compiler errors keep the shared file's line numbers.
        !active() -> return ""
        else -> {
            var l = line
            for ((re, to) in renames) l = re.replace(l, to)
            return l
        }
    }
    return ""
}

val sharedSources = tasks.register<Sync>("sharedSources") {
    into(layout.buildDirectory.dir("generated/shared-src"))
    for (dir in sharedSrc) from(runtimeDir.resolve(dir))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude { !it.isDirectory && ownSrc.resolve(it.relativePath.pathString).exists() }
    inputs.files(fileTree(ownSrc)).withPropertyName("ownSources")
    inputs.property("mc", mc)
    inputs.property("renames", renameTable.toString() + yarnRenameTable.toString())
    filteringCharset = "UTF-8"
    eachFile {
        if (!name.endsWith(".java")) return@eachFile
        val file = path
        val stack = ArrayDeque<BooleanArray>()
        var n = 0
        filter { line -> preprocessLine(line, stack, "$file:${++n}") }
    }
}
// fabric.mod.json and aether.mixins.json are generated: the mixin list is every @Mixin class this version compiles
// (shared files whose //#if excludes this version come out empty), so no version keeps its own list.
val generatedResources = layout.buildDirectory.dir("generated/adapter-resources")
val adapterResources = tasks.register("adapterResources") {
    dependsOn(sharedSources)
    val sources = files(sharedSources.map { it.destinationDir }, ownSrc)
    inputs.files(sources).withPropertyName("sources")
    inputs.property("mc", mc)
    inputs.property("java", javaVersion)
    outputs.dir(generatedResources)
    doLast {
        val mixins = sources.asFileTree.matching { include("gg/aether/client/mixin/*.java") }.files
            .filter { it.readText().contains("@Mixin(") }.map { it.nameWithoutExtension }.sorted().distinct()
        val out = generatedResources.get().asFile
        out.mkdirs()
        val list = mixins.joinToString(",\n") { "    \"$it\"" }
        out.resolve("aether.mixins.json").writeText(
            """
            |{
            |  "required": true,
            |  "minVersion": "0.8",
            |  "package": "gg.aether.client.mixin",
            |  "compatibilityLevel": "JAVA_$javaVersion",
            |  "client": [
            |LIST
            |  ],
            |  "injectors": { "defaultRequire": 1 }
            |}
            """.trimMargin().replace("LIST", list) + "\n")
        out.resolve("fabric.mod.json").writeText(
            """
            {
              "schemaVersion": 1,
              "id": "aether",
              "version": "${'$'}{version}",
              "name": "Aether Client",
              "description": "Aether client mods: HUD, zoom, freelook, crosshair and more. Right Shift opens the mod menu; replaces the title screen with the Aether menu.",
              "license": "All-Rights-Reserved",
              "environment": "client",
              "entrypoints": { "client": ["gg.aether.client.AetherClient"] },
              "mixins": ["aether.mixins.json"],
              "custom": { "aether:build": "${'$'}{build}" },
              "depends": { "fabricloader": ">=0.16.0", "minecraft": "$mc", "java": ">=$javaVersion" }
            }
            """.trimIndent() + "\n")
    }
}

extensions.configure<SourceSetContainer> {
    named("main") {
        java.srcDir(sharedSources)
        resources.srcDir(runtimeDir.resolve("mc-shared/src/main/resources"))
        resources.srcDir(files(generatedResources).builtBy(adapterResources))
    }
}

val shade: Configuration by configurations.creating

dependencies {
    "implementation"(project(":mods"))
    shade(project(":mods"))
}

// Build number on the main menu ("v0.1.0-123"): AETHER_BUILD on CI, otherwise the git commit count.
val buildNumber: String = providers.environmentVariable("AETHER_BUILD").orElse(
    providers.exec {
        commandLine("git", "rev-list", "--count", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.map { it.trim() }
).getOrElse("")

tasks.named<ProcessResources>("processResources") {
    inputs.property("version", project.version)
    inputs.property("build", buildNumber)
    filesMatching("fabric.mod.json") { expand("version" to project.version, "build" to buildNumber) }
}

tasks.named<Jar>("jar") {
    dependsOn(shade)
    // Lazy: resolving `shade` while configuring would lock the repositories before Loom adjusts them (LWJGL upgrade).
    from(provider { shade.map { zipTree(it) } }) { exclude("META-INF/MANIFEST.MF") }
}
