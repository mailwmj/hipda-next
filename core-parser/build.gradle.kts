import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
    inputs.dir(rootProject.layout.projectDirectory.dir("hipda/src/test/resources/samples"))
        .withPropertyName("hipdaTestSamples")
        .withPathSensitivity(PathSensitivity.RELATIVE)
        .optional()
}

dependencies {
    implementation(libs.jsoup)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}
