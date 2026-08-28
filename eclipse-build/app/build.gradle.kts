plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.northernai.eclipsecam"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.northernai.eclipsecam"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0-field"
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
    val cameraX = "1.5.0"
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.10.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.7")
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")

    // Background uploads that survive the app being closed.
    implementation("androidx.work:work-runtime-ktx:2.9.1")
    // Google authorization for the drive.file scope. Keyed on package name + signing
    // certificate, so no client ID or google-services.json belongs in this repo.
    implementation("com.google.android.gms:play-services-auth:21.3.0")
    // Drive REST calls. The official Drive client library drags in a far larger tree.
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
