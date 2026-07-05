plugins {
    kotlin("jvm") version "2.3.20"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "io.github.darkstarworks"

// Two build targets from one source, selected with -Pmc=<line> (default 26):
//   ./gradlew shadowJar -Pmc=21   ->  AntiDupePro-3.4.2.jar       (compile 1.21.x, Java 21)
//   ./gradlew shadowJar -Pmc=26   ->  AntiDupePro-3.4.2-mc26.jar  (compile 26.x,  Java 25)
// 1.21.x servers run JDK21 and can't load Java 25 bytecode, hence the two artifacts.
val pluginVersion = "3.5.0"
val mcLine = (findProperty("mc") as String?) ?: "26"
val is26 = mcLine == "26"
version = if (is26) "$pluginVersion-mc26" else pluginVersion

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
    maven("https://jitpack.io")
}

dependencies {
    // API matches the build target. All server internals are reached via reflection, so the
    // tag stripper needs neither paperweight nor the newer API at compile time.
    compileOnly(
        if (is26) "io.papermc.paper:paper-api:26.1.2.build.66-stable"
        else "io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT"
    )
    // Netty types for the client-side tag stripper's pipeline handler. compileOnly — the server
    // ships Netty at runtime, so nothing is added to the jar. All NMS access is via reflection,
    // so no paperweight/dev-bundle is needed and this still builds on plain paper-api.
    compileOnly("io.netty:netty-transport:4.1.101.Final")
    compileOnly("io.netty:netty-common:4.1.101.Final")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("org.json:json:20231013")

    // PluginPulse — update checking + verified install staging. Spigot-safe:
    // falls back to plain-text notices when Adventure is absent.
    implementation("com.github.darkstarworks.PluginPulse:pluginpulse-core:v0.4.1")
}

tasks {
    runServer {
        minecraftVersion(if (is26) "26.1.2" else "1.21.8")
    }
}

// 1.21.x servers run JDK21 and cannot load newer bytecode; 26.x runs JDK25 and loads either.
// So the 1.21 artifact must be Java 21; the 26 artifact targets Java 25.
val targetJavaVersion = if (is26) 25 else 21
kotlin {
    jvmToolchain(targetJavaVersion)
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
    // Final artifact is the shaded jar itself: AntiDupePro-<version>.jar.
    // No "-all" classifier, and the thin (dependency-less) jar task is disabled
    // below because it is never released or used.
    archiveClassifier.set("")

    // Relocate PluginPulse so it can't clash with another plugin's shaded copy.
    relocate("io.github.darkstarworks.pluginpulse", "io.github.darkstarworks.adp.pluginpulse")

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
    exclude("org/sqlite/native/Windows/aarch64/**")
    exclude("org/sqlite/native/Windows/armv7/**")
    exclude("org/sqlite/native/Windows/x86/**")    // 32-bit Windows

    // Build / tooling artefacts that have no runtime purpose
    exclude("META-INF/com.android.tools/**")   // Android-specific tooling
    exclude("META-INF/proguard/**")            // upstream ProGuard rules
    exclude("META-INF/maven/**")               // dependency POMs / properties
    exclude("META-INF/native-image/**")        // GraalVM hints, we don't native-compile
    exclude("META-INF/versions/*/module-info.class")
    exclude("module-info.class")
}

tasks.processResources {
    val props = mapOf("version" to version)
    inputs.properties(props)
    filteringCharset = "UTF-8"
    filesMatching("plugin.yml") {
        expand(props)
    }
}
