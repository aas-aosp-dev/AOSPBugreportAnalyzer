plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.ktor)
    application
}

group = "com.bigdotdev.aospbugreportanalyzer"
version = "1.0.0"
application {
    mainClass.set("com.bigdotdev.aospbugreportanalyzer.ApplicationKt")

    val isDevelopment: Boolean = project.ext.has("development")
    applicationDefaultJvmArgs = listOf("-Dio.ktor.development=$isDevelopment")
}

dependencies {
    implementation(projects.shared)
    implementation(libs.logback)
    implementation(libs.ktor.serverCore)
    implementation(libs.ktor.serverNetty)
    implementation(libs.ktor.server.contentNegotiation)
    implementation(libs.ktor.client.cio)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.ktor.serverTestHost)
    testImplementation(libs.kotlin.testJunit)
}