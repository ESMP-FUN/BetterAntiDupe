plugins {
    kotlin("jvm") version "2.3.0-RC"
    id("com.gradleup.shadow") version "8.3.0"
    id("xyz.jpenilla.run-paper") version "2.3.1"
}

group = "io.github.darkstarworks"
version = "3.3.1-paper26"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/") {
        name = "papermc-repo"
    }
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:26.1.2.build.66-stable")
    implementation("org.jetbrains.kotlin:kotlin-stdlib-jdk8")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("io.lettuce:lettuce-core:6.3.0.RELEASE")
    implementation("org.xerial:sqlite-jdbc:3.45.3.0")
    implementation("org.json:json:20231013")
}

tasks {
    runServer {
        minecraftVersion("26.1.2")
    }
}

val targetJavaVersion = 25
kotlin {
    jvmToolchain(targetJavaVersion)
}

tasks.build {
    dependsOn("shadowJar")
}

// Trim the shaded jar. sqlite-jdbc ships native binaries for ~23 platforms;
// a Minecraft server only ever runs on a small subset. Everything in here is
// excluded because we cannot reach a state where it gets loaded.
tasks.shadowJar {
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
