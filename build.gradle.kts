plugins {
    id("com.android.application") version "8.2.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}
dependencies {
    // Библиотека для чтения и записи тегов в MP3/FLAC
    implementation("com.github.AdrienPoupa:jaudiotagger:2.3.15")
}
