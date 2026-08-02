plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    application
}

group = "com.deepseek.coder"
version = "1.0.0"

dependencies {
    // 核心：kotlinx-serialization 做 Planner Schema JSON 解析
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.okhttp)
    // Kotlin reflect（类型匹配用）
    implementation(kotlin("reflect"))
    // 测试框架
    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.serialization.json)
}

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.deepseek.coder.planner.bench.RunAllBenchmarksKt")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
