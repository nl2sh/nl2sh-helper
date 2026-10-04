import java.util.Properties

plugins {
    id("com.android.application")
}

val keystoreProperties = Properties().apply {
    val propertiesFile = rootProject.file("keystore.properties")
    if (propertiesFile.exists()) propertiesFile.inputStream().use { load(it) }
}
val signingValues = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").associateWith { key ->
    val envName = when (key) {
        "storeFile" -> "NL2SH_HELPER_KEYSTORE_FILE"
        "storePassword" -> "STORE_PASSWORD"
        "keyAlias" -> "KEY_ALIAS"
        else -> "KEY_PASSWORD"
    }
    providers.environmentVariable(envName).orNull ?: keystoreProperties.getProperty(key)
}
val hasSigning = signingValues.values.any { !it.isNullOrEmpty() }
if (hasSigning) {
    val missing = signingValues.filterValues { it.isNullOrEmpty() }.keys
    require(missing.isEmpty()) { "Incomplete release signing configuration; missing: ${missing.joinToString()}" }
    require(rootProject.file(signingValues.getValue("storeFile")!!).isFile) { "Release keystore file does not exist" }
}
val releaseVersionCode = providers.gradleProperty("nl2shHelperVersionCode").orNull?.let {
    requireNotNull(it.toIntOrNull()?.takeIf { code -> code in 1..2100000000 }) {
        "nl2shHelperVersionCode must be an integer between 1 and 2100000000"
    }
} ?: 1

android {
    namespace = "ernest.nl2sh.helper"
    compileSdk = 36
    defaultConfig {
        applicationId = "ernest.nl2sh.helper"
        minSdk = 26
        targetSdk = 35
        versionCode = releaseVersionCode
        versionName = providers.gradleProperty("nl2shHelperVersionName").orNull ?: "0.1.0"
    }
    signingConfigs {
        if (hasSigning) {
            create("release") {
                storeFile = rootProject.file(signingValues.getValue("storeFile")!!)
                storePassword = signingValues.getValue("storePassword")
                keyAlias = signingValues.getValue("keyAlias")
                keyPassword = signingValues.getValue("keyPassword")
                enableV1Signing = false
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
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
    implementation("com.github.Ernest-su:adb:v0.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("com.google.zxing:core:3.5.4")
    testImplementation("junit:junit:4.13.2")
}
