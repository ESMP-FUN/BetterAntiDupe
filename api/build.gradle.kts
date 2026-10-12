plugins {
    `java-library`
    `maven-publish`
}

group = "com.esmpfun"

repositories {
    mavenCentral()
    maven("https://hub.spigotmc.org/nexus/content/repositories/snapshots/") {
        name = "spigotmc-repo"
    }
}

dependencies {
    // Spigot rather than Paper on purpose: anything that compiles here exists on every server
    // the plugin supports, from Spigot 1.21 to Paper and Folia 26.x.
    compileOnly("org.spigotmc:spigot-api:1.21.11-R0.1-SNAPSHOT")
    compileOnlyApi("org.jspecify:jspecify:1.0.0")
}

java {
    withSourcesJar()
    withJavadocJar()
}

tasks.withType<JavaCompile>().configureEach {
    // The 1.21 jar runs on Java 21, and it carries these classes too.
    options.release.set(21)
    options.encoding = "UTF-8"
}

tasks.javadoc {
    (options as StandardJavadocDocletOptions).apply {
        encoding = "UTF-8"
        addStringOption("Xdoclint:all,-missing", "-quiet")
        links("https://docs.oracle.com/en/java/javase/21/docs/api/")
    }
}

publishing {
    publications {
        create<MavenPublication>("api") {
            artifactId = "betterantidupe-api"
            from(components["java"])
            pom {
                name.set("BetterAntiDupe API")
                description.set("API for other plugins to work with BetterAntiDupe")
                url.set("https://github.com/ESMP-FUN/BetterAntiDupe")
            }
        }
    }
}
