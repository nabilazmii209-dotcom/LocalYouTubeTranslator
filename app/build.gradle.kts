plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    buildFeatures { viewBinding = true }
    namespace = "com.nabil.localtranslator"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nabil.localtranslator"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "1.0-youtube-local"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("dev.ffmpegkit-maintained:whisper-android:1.0.0")
    implementation("com.google.mlkit:translate:17.0.3")
    implementation("com.google.mlkit:language-id:17.0.6")
}
