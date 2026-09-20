plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.zalopollrank"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.zalopollrank"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "1.3"
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
    implementation("androidx.core:core-ktx:1.15.0")
}
