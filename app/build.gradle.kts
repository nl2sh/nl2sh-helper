plugins {
    id("com.android.application")
}

android {
    namespace = "ernest.nl2sh.helper"
    compileSdk = 36
    defaultConfig {
        applicationId = "ernest.nl2sh.helper"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation("com.github.Ernest-su:adb:v0.1.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}
