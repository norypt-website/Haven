plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

kotlin {
    jvmToolchain(17)
    explicitApi()
}

dependencies {
    // Tink for Android ships as a plain jar with no Android runtime dependency, which lets the
    // backup format be unit tested on the JVM.
    implementation(libs.tink.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit)
    testImplementation(libs.truth)
}
