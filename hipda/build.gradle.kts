import org.gradle.api.tasks.testing.Test

plugins {
    alias(libs.plugins.android.application)
}

android {
    namespace = "net.jejer.hipda"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt()) {
            minorApiLevel = libs.versions.compileSdkMinor.get().toInt()
        }
    }

    defaultConfig {
        applicationId = "net.jejer.hipda.ng"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 5005
        versionName = "5.0.05"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.txt",
            )
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
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

androidComponents {
    onVariants(selector().withBuildType("release")) { variant ->
        variant.outputs.forEach { output ->
            output.outputFileName.set("hipda-ng-release.apk")
        }
    }
}

tasks.withType<Test>().configureEach {
    failOnNoDiscoveredTests.set(false)
}

dependencies {
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.preference)
    implementation(libs.material)
    implementation(libs.androidx.work.runtime)
    implementation(libs.androidx.security.crypto)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.swiperefreshlayout)
    implementation(libs.androidx.documentfile)

    implementation(libs.jsoup)

    implementation(platform(libs.okhttp.bom))
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.cookie.store)
    implementation(libs.cookie.store.okhttp)

    implementation(libs.gson)

    implementation(libs.glide)
    implementation(libs.glide.okhttp3.integration)
    annotationProcessor(libs.glide.compiler)

    implementation(libs.android.priority.jobqueue)
    implementation(libs.eventbus)

    implementation(libs.subsampling.scale.image.view)

    implementation(libs.materialdrawer)
    implementation(libs.iconics.core)

    implementation(libs.google.material.typeface) {
        isTransitive = false
    }
    implementation(libs.fontawesome.typeface) {
        isTransitive = false
    }

    implementation(libs.bubble.layout)
    implementation(libs.emoji.java) {
        exclude(group = "org.json", module = "json")
    }

    implementation(libs.calligraphy3)
    implementation(libs.viewpump)

    implementation(libs.compressor)

    implementation(project(":emoji"))
}
