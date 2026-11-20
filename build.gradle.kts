plugins {
    java
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "io.github.sx2xoox"
version = "0.1.0"

val paperApiVersion: String by project
val mockBukkitVersion: String by project
val anthropicSdkVersion: String by project

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    // the server's own netty (the resource pack served on the game port, core.pack.SamePortHost); Paper 26.3's version
    compileOnly("io.netty:netty-transport:4.2.16.Final")
    // the official Claude API SDK for the 증강 봇전 Claude bot (game.augment.bot.claude): loaded by the server at runtime
    // from plugin.yml `libraries:` (not shaded, the jar stays small); keep both versions in sync
    compileOnly("com.anthropic:anthropic-java:$anthropicSdkVersion")

    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("io.netty:netty-transport:4.2.16.Final")
    testImplementation("com.anthropic:anthropic-java:$anthropicSdkVersion")
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

// MCPL 리소스팩: src/main/resourcepack-src (models/*.bbmodel from tools/assetgen, assets/ copied as-is) is converted
// by core.pack.PackBuilder into build/resourcepack/MCPL-pack.zip, which is bundled into the plugin jar. The zip is
// reproducible (sorted entries, fixed timestamps), so the same sources always give the same SHA-1 clients check.
val resourcePackSource = layout.projectDirectory.dir("src/main/resourcepack-src")
val resourcePackZip = layout.buildDirectory.file("resourcepack/MCPL-pack.zip")
val resourcePack = tasks.register<JavaExec>("resourcePack") {
    description = "Builds the MCPL resource pack (build/resourcepack/MCPL-pack.zip, also bundled into the plugin jar)."
    group = "build"
    classpath = sourceSets.main.get().output.classesDirs + configurations.compileClasspath.get()
    mainClass = "io.github.sx2xoox.mcpl.core.pack.PackBuilder"
    inputs.dir(resourcePackSource)
    outputs.file(resourcePackZip)
    args(resourcePackSource.asFile.path, resourcePackZip.get().asFile.path)
}

tasks.processResources {
    val props = mapOf("version" to project.version, "anthropicSdkVersion" to anthropicSdkVersion)
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

tasks.register<Test>("updateSiegeBalance") {
    description = "Regenerates docs/siege-balance.md and the bundled siege-balance.yml corrections (SiegeBalanceReport)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*SiegeBalanceReport")
    }
    systemProperty("mcpl.updateDocs", "true")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("basicAttackLab") {
    description = "Measures every 공성전 basic attack in test matches into docs/basic-attack-lab.md (-Pattacks=a,b: some)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    maxHeapSize = "1g"
    filter {
        includeTestsMatching("*BasicAttackLab")
    }
    systemProperty("mcpl.basicAttackLab", "true")
    systemProperty("mcpl.basicAttackIds", (project.findProperty("attacks") ?: "").toString())
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("siegeLab") {
    description = "Measures every 공성전 pool ability in test matches (SiegeBalanceLab) into docs/siege-balance-lab.csv; " +
        "-Pabilities=a,b re-measures only those. Run updateSiegeBalance afterwards."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*SiegeBalanceLab")
    }
    maxHeapSize = "2g"
    systemProperty("mcpl.siegeLab", "true")
    systemProperty("mcpl.siegeLab.abilities", (project.findProperty("abilities") ?: "").toString())
    // -Pout=<file> -Puntuned=true: measure without the 공성전 tuning into another file (comparisons only)
    systemProperty("mcpl.siegeLab.out", (project.findProperty("out") ?: "").toString())
    systemProperty("mcpl.siegeLab.untuned", (project.findProperty("untuned") ?: "false").toString())
    testLogging {
        showStandardStreams = true
    }
    outputs.upToDateWhen { false }
}

tasks.register<Test>("updateBasicAttackDocs") {
    description = "Regenerates the basic attack (평타) table in docs/siege-v2.md §8 from BasicAttacks."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*BasicAttackDocsTest")
    }
    systemProperty("mcpl.updateDocs", "true")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("updateCreatureAttacks") {
    description = "Regenerates tools/assetgen/models/creature_attacks.json (the attack poses the preview GIFs draw)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*CreatureAttacksExportTest")
    }
    systemProperty("mcpl.updateDocs", "true")
    outputs.upToDateWhen { false }
}

tasks.register<Test>("updateBasicAttackFx") {
    description = "Regenerates tools/assetgen/models/basic_attack_fx.json (the basic attack looks the preview GIFs draw)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*BasicAttackFxExportTest")
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
    description = "Renders preview images of the battle maps to build/map-previews (-Pmap=<id> for one map, " +
        "-Pzone=<name> for one zone)."
    group = "documentation"
    useJUnitPlatform()
    testClassesDirs = sourceSets.test.get().output.classesDirs
    classpath = sourceSets.test.get().runtimeClasspath
    filter {
        includeTestsMatching("*MapPreviewRender")
    }
    systemProperty("mcpl.renderMaps", "true")
    systemProperty("mcpl.renderZone", (project.findProperty("zone") ?: "").toString())
    systemProperty("mcpl.renderMap", (project.findProperty("map") ?: "").toString())
    maxHeapSize = "2g"
    outputs.upToDateWhen { false }
}
