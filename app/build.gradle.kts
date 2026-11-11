plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "ai.esteef.philibert.phone"
    compileSdk = 35

    defaultConfig {
        applicationId = "ai.esteef.philibert.phone"
        minSdk = 29
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            // signed with this computer's debug key: installs by sideloading with no keystore to keep
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
// No libraries: the Android platform alone (WebView, accessibility, org.json, HttpURLConnection).
