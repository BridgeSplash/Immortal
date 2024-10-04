
import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar

plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    id("com.gradleup.shadow") version "8.3.0"

    `maven-publish`
    java
}

repositories {
    mavenCentral()

    maven {
        url = uri("https://www.jitpack.io")
    }
    maven {
        url = uri("https://maven.jeremylvln.fr/repository/shulker-releases")
    }
}

dependencies {
    compileOnly("net.minestom:minestom-snapshots:9fbff439e7")
    testImplementation("net.minestom:minestom-snapshots:9fbff439e7")
    implementation("dev.hollowcube:minestom-ce-extensions:1.2.0")
    testImplementation("dev.hollowcube:minestom-ce-extensions:1.2.0")
    implementation("io.shulkermc:shulker-server-agent:0.11.0:minestom")

    // Util
    api("net.kyori:adventure-text-minimessage:4.12.0")

    // DB
    api("redis.clients:jedis:4.3.1")
    api("org.litote.kmongo:kmongo-coroutine-serialization:4.8.0")
    api("org.litote.kmongo:kmongo-id:4.8.0")

    implementation("ch.qos.logback:logback-core:1.5.8")
    implementation("ch.qos.logback:logback-classic:1.5.8")

    compileOnly("space.vectrix.flare:flare:2.0.1")
    compileOnly("space.vectrix.flare:flare-fastutil:2.0.1")

    // Kotlin
    testImplementation(kotlin("test"))
    compileOnly("org.jetbrains.kotlinx:kotlinx-serialization-json:1.4.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.6.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation(kotlin("stdlib"))
    implementation(kotlin("reflect"))
}

tasks {

    named<ShadowJar>("shadowJar") {
        archiveBaseName.set("immortal")

        mergeServiceFiles()
        minimize()
    }

    build {
        dependsOn(shadowJar)
    }

    test {
        useJUnitPlatform()
    }

    java{
        withJavadocJar()
        withSourcesJar()
    }

    compileKotlin {
        kotlinOptions {
            jvmTarget = JavaVersion.VERSION_21.toString()
            freeCompilerArgs = listOf("-Xinline-classes")
        }
    }
}

publishing {
    repositories{
        maven {
            name = "Tesseract"
            url = uri("https://repo.tesseract.club/releases")
            credentials {
                username = System.getenv("MavenUsername")
                password = System.getenv("MavenPassword")
            }
        }
    }
    publications {
        create<MavenPublication>("maven") {
            groupId = project.properties["group"] as? String?
            artifactId = project.name
            version = project.properties["version"] as? String?

            from(components["java"])
        }
    }
}