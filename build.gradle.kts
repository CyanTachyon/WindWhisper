val logback_version: String by project
val jline_version: String by project

plugins {
    kotlin("jvm") version "2.3.0"
    kotlin("plugin.serialization") version "2.3.0"
    id("io.ktor.plugin") version "3.3.3"
}

group = "moe.tachyon.windwhisper"
version = "1.0.0"

application {
    mainClass.set("moe.tachyon.windwhisper.MainKt")
    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("reflect")) // kotlin 反射库

    // ktor server
    implementation("io.ktor:ktor-server-core")
    implementation("io.ktor:ktor-server-core-jvm") // core
    implementation("io.ktor:ktor-server-netty-jvm") // netty
    implementation("io.ktor:ktor-server-content-negotiation") // request/response时反序列化
    implementation("io.ktor:ktor-server-status-pages") // 错误页面(异常处理)
    implementation("io.ktor:ktor-server-auto-head-response-jvm") // 自动响应HEAD请求
    implementation("io.ktor:ktor-server-double-receive-jvm") // 重复接收
    implementation("io.ktor:ktor-server-call-logging-jvm") // 日志
    implementation("io.ktor:ktor-server-sse") // Server-Sent Events (SSE) 支持
    implementation("io.ktor:ktor-server-config-yaml-jvm") // yaml on read application.yaml

    // ktor client
    implementation("io.ktor:ktor-client-core-jvm") // core
    implementation("io.ktor:ktor-client-cio")
    implementation("io.ktor:ktor-client-content-negotiation") // request/response时反序列化

    // ktor common
    implementation("io.ktor:ktor-serialization-kotlinx-json-jvm") // json on request/response
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.8.1") // json on request/response
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2") // 协程

    implementation("org.apache.pdfbox:pdfbox:2.0.34")

    implementation("org.fusesource.jansi:jansi:2.4.1") // 终端颜色码
    implementation("org.jline:jline:$jline_version") // 终端打印、命令等
    implementation("ch.qos.logback:logback-classic:${logback_version}")  // 日志
    implementation("com.charleskorn.kaml:kaml:0.80.0") // yaml for kotlin on read/write file
    implementation("me.nullaqua:BluestarAPI-kotlin:4.3.7")
    implementation("me.nullaqua:BluestarAPI-kotlin-reflect:4.3.7")

    testImplementation(kotlin("test"))
}

kotlin {
    jvmToolchain(17)

    compilerOptions {
        freeCompilerArgs.add("-Xmulti-dollar-interpolation")
        freeCompilerArgs.add("-Xcontext-parameters")
        freeCompilerArgs.add("-Xcontext-sensitive-resolution")
        freeCompilerArgs.add("-Xnested-type-aliases")
        freeCompilerArgs.add("-Xdata-flow-based-exhaustiveness")
        freeCompilerArgs.add("-Xallow-reified-type-in-catch")
        freeCompilerArgs.add("-Xallow-holdsin-contract")
    }
}

ktor {
    fatJar {
        allowZip64 = true
        archiveFileName = "WindWhisper.jar"
    }
}

tasks.withType<ProcessResources> {
    filesMatching("**/application.yaml") {
        expand(mapOf("version" to version)) {
            escapeBackslash = true
        }
    }
}