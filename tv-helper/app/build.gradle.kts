import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val sign = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.couchtv.helper"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.couchtv.helper"
        minSdk = 26
        targetSdk = 34
        versionCode = 5
        versionName = "1.3.1"
    }

    signingConfigs {
        if (sign.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(sign.getProperty("storeFile"))
                storePassword = sign.getProperty("storePassword")
                keyAlias = sign.getProperty("keyAlias")
                keyPassword = sign.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
