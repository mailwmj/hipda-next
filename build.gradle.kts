plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    // Puts KGP 2.4.21 on the build classpath for AGP 9 built-in Kotlin without applying legacy kotlin-android.
    alias(libs.plugins.kotlin.jvm) apply false
}
