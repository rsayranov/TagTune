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
    // Инструменты AndroidX (ContextCompat, ActivityCompat для разрешений)
    implementation("androidx.core:core-ktx:1.12.0")

    // Библиотека чтения/записи ID3-тегов в MP3 (наше поле COMMENT)
    implementation("com.mpatric:mp3agic:0.9.1")
}
