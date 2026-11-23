plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.nasir.nkadblockvpn"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.nasir.nkadblockvpn"
        minSdk = 23
        targetSdk = 35
        versionCode = 2
        versionName = "1.0.1"
    }

    // Keep Java and Kotlin bytecode targets aligned for GitHub Actions.
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
