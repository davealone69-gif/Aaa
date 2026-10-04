plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.aura.avatarstudio"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aura.avatarstudio"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    // No external dependencies: the renderer is 100% android.* + org.json
    // (org.json ships inside the Android framework).
    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }
}
