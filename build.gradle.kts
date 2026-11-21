import org.gradle.api.attributes.java.TargetJvmVersion
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension
import proguard.gradle.ProGuardTask

buildscript {
    repositories {
        mavenCentral()
    }
    dependencies {
        // 7.9.1+ reads Java 25 class files (the Paper 26.x API).
        classpath("com.guardsquare:proguard-gradle:7.10.0")
        classpath("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20")
    }
    configurations.classpath {
        resolutionStrategy.force("org.jetbrains.kotlin:kotlin-metadata-jvm:2.4.20")
    }
}

plugins {
    kotlin("jvm") version "2.4.20"
    id("com.gradleup.shadow") version "9.6.1"
    id("xyz.jpenilla.run-paper") version "3.1.0"
}

group = "com.esmpfun"
version = "4.9.5"
project(":api").version = version

// ============================================================================
// One jar for every supported server (Paper, Folia and Spigot, 1.21 and newer):
//   ./gradlew build     build/libs/BetterAntiDupe-<version>.jar
//
// Each subproject compiles against its own Paper API. common holds everything
// that is the same everywhere and is held to the 1.21 API, so nothing newer
// sneaks in. At startup ServerAdapters picks the newest adapter that fits.
// ============================================================================
val paper121 = "1.21.11-R0.1-SNAPSHOT"
val paper262 = "26.2.build.129-stable"
val paper263 = "26.3.build.249-stable"
// TODO(26.4): no Paper 26.4 API is published yet. Point this at it once it is.
val paper264 = (findProperty("paper264Api") as String?) ?: paper263

val paperApi = mapOf(
    ":common" to paper121,
    ":adapter-v1_21" to paper121,
    // The first Paper build with the whole Sulfur Cube API.
    ":adapter-v26_2" to paper262,
    ":adapter-v26_4" to paper264,
)

allprojects {
    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
        maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/")
        maven("https://jitpack.io") {
            content { includeGroup("com.github.darkstarworks.PluginPulse") }
        }
        maven("https://repo.faststats.dev/releases") {
            content { includeGroupByRegex("dev\\.faststats.*") }
        }
    }
}

// The api module keeps its own plain Java build, so other plugins see the same artifact as before.
val kotlinProjects = listOf(rootProject) + paperApi.keys.map { project(it) }

kotlinProjects.forEach { p ->
    if (p != rootProject) p.apply(plugin = "org.jetbrains.kotlin.jvm")

    // JDK 25 compiles everything (Paper 26.x publishes Java 25 jars only, and Gradle 9
    // enforces the attribute); the output stays Java 21 so 1.21 servers can load it.
    p.configure<KotlinJvmProjectExtension> {
        jvmToolchain(25)
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
            freeCompilerArgs.add("-Xjdk-release=21")
        }
    }
    p.tasks.withType<JavaCompile>().configureEach { options.release.set(21) }
    p.configurations.matching { it.isCanBeResolved }.configureEach {
        attributes { attribute(TargetJvmVersion.TARGET_JVM_VERSION_ATTRIBUTE, 25) }
    }
}

paperApi.forEach { (path, api) ->
    project(path).dependencies {
        "compileOnly"("io.papermc.paper:paper-api:$api")
        if (path != ":common") "compileOnly"(project(":common"))
    }
}

project(":adapter-v26_4").dependencies {
    "implementation"(project(":adapter-v26_2"))
}

project(":common") {
    dependencies {
        "implementation"(project(":api"))
        // Netty types for the client-side tag stripper's pipeline handler. The server ships
        // Netty, so nothing is added to the jar; all server internals are reached by reflection.
        "compileOnly"("io.netty:netty-transport:4.1.101.Final")
        "compileOnly"("io.netty:netty-common:4.1.101.Final")
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        // Lettuce's coroutine command API bridges its Reactor types to suspend functions through
        // kotlinx-coroutines-reactive. Lettuce declares it optional, so it is not pulled transitively
        // and must be listed here or the Redis backend dies with NoClassDefFoundError on enable.
        "implementation"("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.11.0")
        "implementation"("io.lettuce:lettuce-core:7.7.0.RELEASE")
        // Every Paper and Spigot server ships sqlite-jdbc, and the plugin class loader asks the server
        // first, so a shaded copy was never loaded. Compiled against the oldest bundled version.
        "compileOnly"("org.xerial:sqlite-jdbc:3.49.1.0")
        "implementation"("org.json:json:20260814")
        // Update checking and verified install staging. Falls back to plain text without Adventure.
        "implementation"("com.github.darkstarworks.PluginPulse:pluginpulse-core:v0.8.0")
        // Anonymous usage metrics and (opt-in) error reporting, both switchable in config.yml.
        "implementation"("dev.faststats.metrics:bukkit:0.28.0")
    }
}

// Tests of code run in common (they reach its internal parts), tests of the bundled files in the root.
listOf(rootProject, project(":common")).forEach { p ->
    p.dependencies {
        "testImplementation"(kotlin("test"))
        "testImplementation"("io.papermc.paper:paper-api:$paper121")
    }
    p.tasks.withType<Test>().configureEach { useJUnitPlatform() }
}

val proguardLibraries: Configuration = configurations.create("proguardLibraries")
// Read before the Paper API, so ProGuard sees Bukkit's classes as Spigot has them. Otherwise a stack
// frame it works out can name a Paper-only type, and Spigot then refuses to load that class.
val proguardSpigot: Configuration = configurations.create("proguardSpigot")

dependencies {
    implementation(project(":api"))
    implementation(project(":common"))
    implementation(project(":adapter-v1_21"))
    implementation(project(":adapter-v26_2"))
    implementation(project(":adapter-v26_4"))

    proguardSpigot("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT") { isTransitive = false }
    proguardLibraries("io.papermc.paper:paper-api:$paper264")
    proguardLibraries("io.netty:netty-transport:4.1.101.Final")
    proguardLibraries("io.netty:netty-codec:4.1.101.Final")
    proguardLibraries("io.netty:netty-handler:4.1.101.Final")
    proguardLibraries("org.xerial:sqlite-jdbc:3.49.1.0")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") { expand(props) }
}

tasks.jar { enabled = false }

// The full jar before shrinking; only an input for proguardJar.
tasks.shadowJar {
    archiveClassifier.set("full")
    destinationDirectory.set(layout.buildDirectory.dir("shadow"))
    from("SOURCE-AVAILABLE.txt")

    // Relocate PluginPulse so it can't clash with another plugin's shaded copy.
    relocate("io.github.darkstarworks.pluginpulse", "io.github.darkstarworks.adp.pluginpulse")
    // Several plugins on one server each shade their own FastStats; unrelocated they'd fight.
    relocate("dev.faststats", "com.esmpfun.antidupe.libs.faststats")
    // org.json is the classic shaded-jar collision: whichever copy loads first wins for everybody.
    relocate("org.json", "com.esmpfun.antidupe.libs.json")
    // Only the Redis backend uses these, so another plugin's copy must not decide how ours behaves.
    relocate("io.lettuce", "com.esmpfun.antidupe.libs.lettuce")
    relocate("reactor", "com.esmpfun.antidupe.libs.reactor")

    // Netty is deliberately NOT relocated. The packet tag stripper works against the server's
    // own Netty pipeline, so its types have to stay the ones the server loaded. The server
    // provides Netty and the plugin class loader asks it first, so only the DNS modules
    // Lettuce needs and the server lacks are ever loaded from this jar.
    exclude {
        it.path.startsWith("io/netty/") && !it.path.startsWith("io/netty/resolver/dns/") &&
            !it.path.startsWith("io/netty/handler/codec/dns/")
    }

    exclude("META-INF/com.android.tools/**")
    exclude("META-INF/proguard/**")
    exclude("META-INF/maven/**")
    exclude("META-INF/native-image/**")
    exclude("META-INF/versions/**")
    exclude("module-info.class")
    exclude("META-INF/*.kotlin_module")

    // lettuce-core bundles several Netty modules, each shipping an identical
    // io.netty.versions.properties; Paper 1.21.11's plugin remapper aborts on the duplicate
    // archive entries (issue #1). The server provides its own Netty, so drop them.
    exclude("META-INF/io.netty.versions.properties")
    exclude("META-INF/services/reactor.blockhound.integration.BlockHoundIntegration")

    // Lettuce 7 finds its JSON parser through ServiceLoader, so the service files are
    // relocated too, or RESP3 replies fail to parse.
    mergeServiceFiles()
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

val shippedJar = layout.buildDirectory.file("libs/${rootProject.name}-$version.jar")

val proguardJar = tasks.register<ProGuardTask>("proguardJar") {
    dependsOn(tasks.shadowJar)
    injars(tasks.shadowJar.flatMap { it.archiveFile })
    outjars(shippedJar)
    configuration("proguard-rules.pro")

    // JDK 21's modules: the oldest Java the jar runs on, and JDK 25 ships without jmods.
    val jmods = javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) }
        .map { it.metadata.installationPath.dir("jmods") }
    listOf("java.base", "java.logging", "java.sql", "java.naming", "java.management", "java.net.http", "jdk.unsupported")
        .forEach { m -> libraryjars(jmods.map { it.file("$m.jmod") }) }
    libraryjars(proguardSpigot)
    libraryjars(proguardLibraries)
}

tasks.assemble { dependsOn(proguardJar) }

runPaper {
    disablePluginJarDetection()
}

tasks.runServer {
    dependsOn(proguardJar)
    // ./gradlew runServer -PrunMc=1.21.1 boots the same jar on another version.
    val mc = (findProperty("runMc") as String?) ?: "26.3"
    minecraftVersion(mc)
    // 1.21 servers run on Java 21, 26.x on Java 25.
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(if (mc.startsWith("1.")) 21 else 25))
    })
    pluginJars(shippedJar)
    // Runs in the shared test server for this version, see ../test-servers/README.md.
    runDirectory.set(file("../test-servers/paper-$mc"))
    // A smoke-test server on an empty world needs very little; uncapped, Paperclip can die
    // allocating a default heap on a machine already running a game and a real server.
    jvmArgs("-DbundlerRepoDir=${file("../test-servers/shared").path}", "-Xms256M", "-Xmx1G")
}
