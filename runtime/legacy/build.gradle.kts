plugins { `java-library` }

allprojects {
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    tasks.withType<JavaCompile> { options.release = 8; options.encoding = "UTF-8" }
    // core and mods also belong to the main build in ../: keep this build's outputs apart.
    if (name == "core" || name == "mods") layout.buildDirectory.set(rootProject.layout.buildDirectory.dir(name))
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
