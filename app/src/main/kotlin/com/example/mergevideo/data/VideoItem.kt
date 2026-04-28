package com.example.mergevideo.data

import android.net.Uri

data class VideoItem(
    val uri: Uri,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long
)
