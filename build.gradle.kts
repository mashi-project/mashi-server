import java.util.Properties

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(ktorLibs.plugins.ktor)
    alias(libs.plugins.kotlin.serialization)
    id("com.github.gmazzo.buildconfig") version "5.3.5"
}

group = "com.mashiverse"
version = "1.0.0-SNAPSHOT"

application {
    mainClass = "io.ktor.server.netty.EngineMain"
}

kotlin {
    jvmToolchain(21)
}

buildConfig {
    val localProperties = Properties()
    val localPropertiesFile = File(rootDir, "keys.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use {
            localProperties.load(it)
        }
    }

    val cleanProperties = localProperties.entries.associate { (key, value) ->
        val rawKey = key.toString()
        val cleanedKey = rawKey.replace(Regex("^[^A-Z0-9_]+"), "")
        cleanedKey to value.toString()
    }

    val discordToken = cleanProperties["DISCORD_TOKEN"] ?: ""
    val mashitKey = cleanProperties["MASHIT_API_KEY"] ?: ""

    buildConfigField("String", "DISCORD_TOKEN", "\"$discordToken\"")
    buildConfigField("String", "MASHIT_API_KEY", "\"$mashitKey\"")
}

dependencies {
    implementation(ktorLibs.serialization.kotlinx.json)
    implementation(ktorLibs.server.config.yaml)
    implementation(ktorLibs.server.contentNegotiation)
    implementation(ktorLibs.server.core)
    implementation(ktorLibs.server.cors)
    implementation(ktorLibs.server.httpRedirect)
    implementation(ktorLibs.client.logging)
    implementation(ktorLibs.server.netty)
    implementation(libs.h2database.h2)
    implementation(ktorLibs.client.cio)
    implementation(libs.koin.ktor)
    implementation(libs.koin.loggerSlf4j)
    implementation(libs.logback.classic)
    implementation(libs.postgresql)

    testImplementation(kotlin("test"))
    testImplementation(ktorLibs.server.testHost)

    // Playwright
    implementation("com.microsoft.playwright:playwright:1.59.0")

    // Kord
    implementation("dev.kord:kord-core:0.18.1")

    // Admin
    implementation("com.google.firebase:firebase-admin:9.3.0")

    // OpenCV
    implementation("com.sksamuel.scrimage:scrimage-core:4.1.3")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.1.3")
    implementation("org.openpnp:opencv:4.9.0-0")

    // Postgres
    implementation("org.jetbrains.exposed:exposed-core:0.50.0")
    implementation("org.jetbrains.exposed:exposed-jdbc:0.50.0")
    implementation("com.zaxxer:HikariCP:5.1.0")
    implementation("org.postgresql:postgresql:42.7.12")
    testImplementation("io.ktor:ktor-server-test-host-jvm:3.5.0")

    // ImageIO
    implementation("com.twelvemonkeys.imageio:imageio-core:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-webp:3.12.0")
    implementation("com.twelvemonkeys.imageio:imageio-metadata:3.12.0")
}
