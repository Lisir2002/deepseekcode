plugins {
    kotlin("jvm") version "2.0.20"
    kotlin("plugin.serialization") version "2.0.20"
    application
}

group = "com.deepseek.coder"
version = "1.0.0"

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation(kotlin("reflect"))
    testImplementation(kotlin("test"))
    testImplementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.1")
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.deepseek.coder.planner.bench.RunAllBenchmarksKt")
}

tasks.test {
    useJUnitPlatform()
}
