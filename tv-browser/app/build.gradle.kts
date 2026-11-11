plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.couchtv.browser"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.couchtv.browser"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // keystore/debug.keystore (git-ignored) keeps updates on the same key as the APK already on
    // the TV; without it the build uses Android's own debug key.
    signingConfigs {
        if (rootProject.file("keystore/debug.keystore").exists()) {
            create("local") {
                storeFile = rootProject.file("keystore/debug.keystore")
                storePassword = "android"
                keyAlias = "androiddebugkey"
                keyPassword = "android"
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("local") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}

dependencies {
    implementation("androidx.webkit:webkit:1.12.1")
}
