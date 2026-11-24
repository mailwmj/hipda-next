import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "com.vanniktech.emoji"
    compileSdk = libs.versions.compileSdk.get().toInt()

    defaultConfig {
        minSdk = libs.versions.minSdk.get().toInt()
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    resourcePrefix = "emoji"

    lint {
        abortOnError = false
        targetSdk = libs.versions.targetSdk.get().toInt()
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

kotlin {
    jvmToolchain(17)
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests.set(false)
}

dependencies {
    implementation(libs.androidx.appcompat)
}
