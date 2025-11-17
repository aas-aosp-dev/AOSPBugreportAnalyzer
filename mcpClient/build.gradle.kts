plugins {
    kotlin("jvm") version libs.versions.kotlin.get()
    application
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

dependencies {
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.core)
}

application {
    mainClass.set("com.bigdotdev.aospbugreportanalyzer.mcp.McpClientMainKt")
}
