import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// Version lives in version.properties so deploy.sh can bump it.
val ver = Properties().apply { rootProject.file("version.properties").inputStream().use { load(it) } }
// The stick's address for this home (bridge.properties, git-ignored: host=192.0.2.10). Left out,
// the app finds the bridge on the Wi-Fi by itself.
val home = Properties().apply {
    val f = rootProject.file("bridge.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
// Release key: signing.properties (git-ignored), with storeFile, storePassword, keyAlias, keyPassword.
val sign = Properties().apply {
    val f = rootProject.file("signing.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "app.couchtv.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.couchtv.phone"
        minSdk = 23            // Samsung J7 Prime and newer
        targetSdk = 34
        versionCode = ver.getProperty("code").toInt()
        versionName = ver.getProperty("name")
        buildConfigField("String", "DEFAULT_HOST", "\"" + (home.getProperty("host") ?: "") + "\"")
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

    buildFeatures { buildConfig = true }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    lint { checkReleaseBuilds = false; abortOnError = false }
}
