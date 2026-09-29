plugins { `java-library` }

allprojects {
    repositories { mavenCentral() }
}

subprojects {
    apply(plugin = "java-library")
    tasks.withType<JavaCompile> { options.release = 8; options.encoding = "UTF-8" }
    dependencies {
        "testImplementation"(platform("org.junit:junit-bom:5.11.3"))
        "testImplementation"("org.junit.jupiter:junit-jupiter")
        "testRuntimeOnly"("org.junit.platform:junit-platform-launcher")
    }
    tasks.withType<Test> { useJUnitPlatform() }
}
