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
    // AndroidX Core KTX - именно здесь живут ContextCompat и ActivityCompat!
    implementation("androidx.core:core-ktx:1.12.0")
    
    // Наша надежная библиотека для MP3-тегов
    implementation("com.mpatric:mp3agic:0.9.1")
}
