plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Set by GitHub Actions (see .github/workflows/build.yml). Local builds use the defaults below.
val ciVersionCode = System.getenv("VERSION_CODE")?.toIntOrNull()
val ciVersionName = System.getenv("VERSION_NAME")
val signingKeystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
val signingPassword = System.getenv("SIGNING_PASSWORD")

android {
    namespace = "com.cpagency.wifimanager"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cpagency.wifimanager"
        minSdk = 24
        targetSdk = 34
        versionCode = ciVersionCode ?: 2
        versionName = ciVersionName ?: "1.1"
    }

    // Same key on every build, so the phone accepts each new APK as an update.
    signingConfigs {
        if (signingKeystorePath != null && signingPassword != null) {
            create("release") {
                storeFile = file(signingKeystorePath)
                storePassword = signingPassword
                keyAlias = "routermanager"
                keyPassword = signingPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
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
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
