plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val build = (project.findProperty("buildNumber") as String?)?.toIntOrNull() ?: 1

android {
    namespace = "com.aod.personal"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.aod.personal"
        minSdk = 26
        targetSdk = 34
        versionCode = build
        versionName = "1.1.$build"
    }
    // Stable signing key (supplied by CI from GitHub secrets) so each build installs over the last one.
    val ksPath = System.getenv("AOD_KEYSTORE")
    if (ksPath != null) {
        signingConfigs {
            create("aod") {
                storeFile = file(ksPath)
                storePassword = System.getenv("AOD_KEYSTORE_PASSWORD")
                keyAlias = "aod"
                keyPassword = System.getenv("AOD_KEYSTORE_PASSWORD")
            }
        }
        buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("aod") } }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
