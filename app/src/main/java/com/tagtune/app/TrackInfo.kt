package com.tagtune.app

data class TrackInfo(
    val uri: String,
    val fileName: String,
    val title: String?,
    val artist: String?,
    val album: String?,
    val genre: String?,
    val format: String?,
    val quality: String?
)
