plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.tagtune2"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.tagtune2"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
}

dependencies {
    // Наша библиотека для тегов теперь на своем месте!
    implementation("com.github.AdrienPoupa:jaudiotagger:2.3.15")
}
