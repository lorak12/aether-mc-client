plugins { `java-library` }

// The cosmetics guide (docs/COSMETICS_GUIDE.md) ships in the jar: the importer's creator kit writes it out.
tasks.named<ProcessResources>("processResources") {
    from(projectDir.resolve("../../docs/COSMETICS_GUIDE.md")) {
        into("aether")
        rename { "cosmetics-guide.md" }
    }
}
