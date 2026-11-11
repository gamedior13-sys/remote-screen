plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.remotescreen"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.remotescreen"
        minSdk = 26
        targetSdk = 34
        // CI build number → each published APK has a higher version (used by the in-app updater)
        val run = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 100 + run
        versionName = "1.$run"
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
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
    // Prebuilt Google WebRTC for Android (maintained build, package org.webrtc)
    implementation("io.github.webrtc-sdk:android:125.6422.07")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.2")
    // Firebase Cloud Messaging: gọi dậy app kể cả khi Android đã giết tiến trình (kết hợp
    // WakeMessagingService + FcmInitProvider khởi tạo thủ công, KHÔNG cần google-services plugin).
    implementation("com.google.firebase:firebase-messaging:24.0.3")
}
