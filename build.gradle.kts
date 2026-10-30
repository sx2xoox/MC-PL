plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.github.sx2xoox"
version = "0.1.0"

val paperApiVersion: String by project
val mockBukkitVersion: String by project

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.mockbukkit.mockbukkit:mockbukkit-v26.3:$mockBukkitVersion")
    testImplementation(platform("org.junit:junit-bom:6.1.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion = JavaLanguageVersion.of(25)
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = Charsets.UTF_8.name()
    options.release = 25
    options.compilerArgs.addAll(listOf("-Xlint:all", "-Xlint:-serial", "-Xlint:-processing", "-Xlint:-classfile"))
}

// 증강 선택 화면 리소스팩: src/main/resourcepack (tools/resourcepack/generate_pack.py 로 생성) → MCPL-pack.zip.
// Reproducible (fixed order and timestamps), so the same sources always give the same SHA-1 the clients check.
val resourcePack = tasks.register<Zip>("resourcePack") {
    description = "Zips the MCPL resource pack to build/resourcepack/MCPL-pack.zip (also bundled into the plugin jar)."
    group = "build"
    from("src/main/resourcepack")
    archiveFileName = "MCPL-pack.zip"
    destinationDirectory = layout.buildDirectory.dir("resourcepack")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.processResources {
    val props = mapOf("version" to project.version)
    inputs.properties(props)
    filteringCharset = Charsets.UTF_8.name()
    filesMatching("plugin.yml") {
        expand(props)
    }
    from(resourcePack) {
        into("resourcepack")
    }
}

tasks.test {
    useJUnitPlatform()
    // the bot matches survey terrain in mock worlds that keep every block they were asked for
    maxHeapSize = "1g"
}

tasks.runServer {
    // ./gradlew runServer 로 테스트 서버를 바로 띄울 수 있습니다.
    minecraftVersion("26.3")
}

tasks.register<Test>("updateAbilityDocs") {
    description = "Regenerates docs/abilities.md from the ability definitions (AbilityInfo)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*AbilityDocsTest")
    }
    systemProperty("mcpl.updateDocs", "true")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("updateAugmentDocs") {
    description = "Regenerates docs/augments.md from the augment definitions (AugmentInfo, BonusCondition)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*AugmentDocsTest")
    }
    systemProperty("mcpl.updateDocs", "true")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("renderMaps") {
    description = "Renders preview images of the battle maps to build/map-previews (-Pzone=<name> for one zone)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*MapPreviewRender")
    }
    systemProperty("mcpl.renderMaps", "true")
    systemProperty("mcpl.renderZone", (project.findProperty("zone") ?: "").toString())
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
}
