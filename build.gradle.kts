plugins {
    kotlin("jvm") version "2.3.21"
    id("com.gradleup.shadow") version "9.0.0"
    // 3.x talks to Paper's fill/v3 downloads API. 2.3.1 used api.papermc.io/v2,
    // which Paper sunset — every version lookup fails with "Unknown Paper Version".
    id("xyz.jpenilla.run-paper") version "3.0.2"
}

group = "com.esmpfun"

// Three build targets from one source, selected with -Pmc=<line> (default 26):
//   ./gradlew shadowJar -Pmc=21   ->  BetterAntiDupe-4.3.0.jar         (1.21.x,       Java 21)
//   ./gradlew shadowJar -Pmc=26   ->  BetterAntiDupe-4.3.0-mc26.jar    (26.0 to 26.2, Java 25)
//   ./gradlew shadowJar -Pmc=263  ->  BetterAntiDupe-4.3.0-mc263.jar   (26.3,         Java 25)
// 1.21.x servers run JDK21 and can't load Java 25 bytecode. The 26.3 jar declares
// api-version 26.3, so an older server refuses it, and it follows its own update track.
val pluginVersion = "4.3.0"
val mcLine = (findProperty("mc") as String?) ?: "26"

data class McTarget(val paperApi: String, val suffix: String, val apiVersion: String, val java: Int, val runMc: String)

val mcTarget = when (mcLine) {
    "21" -> McTarget("1.21.11-R0.1-SNAPSHOT", "", "1.21", 21, "1.21.8")
    "26" -> McTarget("26.1.2.build.66-stable", "-mc26", "1.21", 25, "26.2")
    "263" -> McTarget("26.3.build.4-alpha", "-mc263", "26.3", 25, "26.3")
    else -> throw GradleException("Unknown -Pmc=$mcLine. Use 21, 26 or 263.")
}
version = "$pluginVersion${mcTarget.suffix}"
// PluginPulse only applies a track on 26.x servers, so the 1.21 jar's value is never read.
val updateTrack = if (mcLine == "263") "mc263" else "mc26"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://jitpack.io")
    maven("https://repo.faststats.dev/releases") {
        name = "faststatsReleases"
    }
}

dependencies {
    // API matches the build target. All server internals are reached via reflection, so the
    // tag stripper needs neither paperweight nor the newer API at compile time.
    compileOnly("io.papermc.paper:paper-api:${mcTarget.paperApi}")
    // Netty types for the client-side tag stripper's pipeline handler. compileOnly — the server
    // ships Netty at runtime, so nothing is added to the jar. All NMS access is via reflection,
    // so no paperweight/dev-bundle is needed and this still builds on plain paper-api.
    compileOnly("io.netty:netty-transport:4.1.101.Final")
    compileOnly("io.netty:netty-common:4.1.101.Final")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    // Lettuce's coroutine command API bridges its Reactor types to suspend functions through
    // kotlinx-coroutines-reactive. Lettuce declares it optional, so it is not pulled transitively
    // and must be listed here or the Redis backend dies with NoClassDefFoundError on enable.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-reactive:1.11.0")
    implementation("io.lettuce:lettuce-core:7.7.0.RELEASE")
    implementation("org.xerial:sqlite-jdbc:3.53.4.0")
    implementation("org.json:json:20260814")

    // PluginPulse — update checking + verified install staging. Spigot-safe:
    // falls back to plain-text notices when Adventure is absent.
    implementation("com.github.darkstarworks.PluginPulse:pluginpulse-core:v0.8.0")

    // Unit tests. The ledger's hashing, canonical metadata form and balance arithmetic are
    // pure functions, so they can be checked without standing a server up.
    testImplementation(kotlin("test"))
    testImplementation("io.papermc.paper:paper-api:${mcTarget.paperApi}")

    // FastStats — anonymous usage metrics and (opt-in) error reporting.
    // Server owners can disable either in config.yml; the SDK itself only
    // offers a -Dfaststats.enabled=false JVM flag, which few admins would find.
    implementation("dev.faststats.metrics:bukkit:0.28.0")
}

tasks.test {
    useJUnitPlatform()
}

tasks {
    runServer {
        // Overridable so the same jar can be smoke-tested across the line it claims to
        // support, e.g. ./gradlew runServer -Pmc=26 -PrunMc=26.1.2.
        minecraftVersion((findProperty("runMc") as String?) ?: mcTarget.runMc)
        // A smoke-test server on an empty world needs very little. Left uncapped it asks for
        // a default heap that a development machine already running a game and a real server
        // cannot commit, and Paperclip dies allocating before the plugin ever loads.
        jvmArgs("-Xms256M", "-Xmx1G")
    }
}

kotlin {
    jvmToolchain(mcTarget.java)
}

tasks.build {
    dependsOn("shadowJar")
}

// The plain (non-shaded) jar has no runtime use - skip building it entirely.
tasks.jar {
    enabled = false
}

// Trim the shaded jar. sqlite-jdbc ships native binaries for ~23 platforms;
// a Minecraft server only ever runs on a small subset. Everything in here is
// excluded because we cannot reach a state where it gets loaded.
tasks.shadowJar {
    // Final artifact is the shaded jar itself: BetterAntiDupe-<version>.jar.
    // No "-all" classifier, and the thin (dependency-less) jar task is disabled
    // below because it is never released or used.
    archiveClassifier.set("")

    // Relocate PluginPulse so it can't clash with another plugin's shaded copy.
    relocate("io.github.darkstarworks.pluginpulse", "io.github.darkstarworks.adp.pluginpulse")

    // Same for the FastStats SDK — several plugins on one server will each
    // shade their own copy, and unrelocated they'd fight over the class names.
    relocate("dev.faststats", "com.esmpfun.antidupe.libs.faststats")

    // org.json is the classic shaded-jar collision: plenty of plugins bundle a copy, the
    // versions differ, and whichever one loads first wins for everybody. Relocating ours means
    // the ledger reads and writes with the version it was built against, whatever else is on
    // the server.
    relocate("org.json", "com.esmpfun.antidupe.libs.json")

    // Lettuce and the Reactor runtime it is built on are only ever used by the Redis backend,
    // and nothing outside this plugin touches those objects. Relocating them keeps a second
    // plugin's copy of either from deciding how our Redis client behaves.
    relocate("io.lettuce", "com.esmpfun.antidupe.libs.lettuce")
    relocate("reactor", "com.esmpfun.antidupe.libs.reactor")

    // Netty is deliberately NOT relocated. The packet tag stripper works against the server's
    // own Netty pipeline, so its types have to stay the ones the server loaded.

    // SQLite native binaries — keep only platforms that realistically host
    // a Paper / Spigot server. Saves ~13 MB of jar.
    exclude("org/sqlite/native/Linux-Android/**")  // Minecraft server doesn't run on Android
    exclude("org/sqlite/native/FreeBSD/**")        // vanishingly rare for MC hosting
    exclude("org/sqlite/native/Linux/arm/**")      // 32-bit ARM, modern MC needs 64-bit
    exclude("org/sqlite/native/Linux/armv6/**")
    exclude("org/sqlite/native/Linux/armv7/**")
    exclude("org/sqlite/native/Linux/x86/**")      // 32-bit Linux (glibc)
    exclude("org/sqlite/native/Linux-Musl/x86/**") // 32-bit Linux (musl / Alpine)
    exclude("org/sqlite/native/Linux/ppc64/**")    // PowerPC
    // Linux/riscv64 appeared in sqlite-jdbc 3.53 and is deliberately KEPT (about 760 KB).
    // RISC-V Linux boxes are rare for game hosting but they do exist, and dropping a platform
    // means that server cannot start at all. Re-check this list whenever sqlite-jdbc is bumped:
    // a platform added upstream is shipped silently unless someone looks.
    exclude("org/sqlite/native/Windows/aarch64/**")
    exclude("org/sqlite/native/Windows/armv7/**")
    exclude("org/sqlite/native/Windows/x86/**")    // 32-bit Windows

    // Build / tooling artefacts that have no runtime purpose
    exclude("META-INF/com.android.tools/**")   // Android-specific tooling
    exclude("META-INF/proguard/**")            // upstream rules for a tool we do not run
    exclude("META-INF/maven/**")               // dependency POMs / properties
    exclude("META-INF/native-image/**")        // GraalVM hints, we don't native-compile
    exclude("META-INF/versions/*/module-info.class")
    exclude("module-info.class")

    // lettuce-core bundles several Netty modules, each shipping an identical
    // META-INF/io.netty.versions.properties (plus a BlockHound service file).
    // Shadow keeps every copy, and Paper 1.21.11's plugin remapper aborts on the
    // duplicate archive entries (issue #1). None of these are needed at runtime —
    // the server provides its own Netty — so drop them.
    exclude("META-INF/io.netty.versions.properties")
    exclude("META-INF/services/reactor.blockhound.integration.BlockHoundIntegration")

    // Relocate the ServiceLoader manifests too - both the file names and the class
    // names they list. Lettuce 7 finds its JSON parser (io.lettuce.core.json.JsonParser)
    // through ServiceLoader, so without this the relocated interface has no provider on
    // the relocated path and RESP3 replies fail to parse.
    mergeServiceFiles()

    // Belt-and-braces: if any other duplicate resource slips through, keep the
    // first and drop the rest rather than emitting a duplicate entry.
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

tasks.processResources {
    val props = mapOf("version" to version, "apiVersion" to mcTarget.apiVersion, "track" to updateTrack)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching(listOf("plugin.yml", "pluginpulse.yml")) {
        expand(props)
    }
}
