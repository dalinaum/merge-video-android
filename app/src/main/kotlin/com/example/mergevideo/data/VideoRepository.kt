package com.example.mergevideo.data

import android.content.ContentUris
import android.content.Context
import android.os.Build
import android.provider.MediaStore

class VideoRepository(private val context: Context) {

    fun loadCameraVideos(): List<VideoItem> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI

        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED
        )

        val (selection, selectionArgs) = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            "${MediaStore.Video.Media.MIME_TYPE} = ? AND ${MediaStore.Video.Media.RELATIVE_PATH} LIKE ?" to
                arrayOf("video/mp4", "DCIM/Camera/%")
        } else {
            @Suppress("DEPRECATION")
            "${MediaStore.Video.Media.MIME_TYPE} = ? AND ${MediaStore.Video.Media.DATA} LIKE ?" to
                arrayOf("video/mp4", "%/DCIM/Camera/%")
        }

        val sortOrder = "${MediaStore.Video.Media.DATE_ADDED} DESC"

        val items = mutableListOf<VideoItem>()
        context.contentResolver.query(collection, projection, selection, selectionArgs, sortOrder)?.use { cursor ->
            val idCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)

            while (cursor.moveToNext()) {
                val id = cursor.getLong(idCol)
                val uri = ContentUris.withAppendedId(collection, id)
                items += VideoItem(
                    uri = uri,
                    displayName = cursor.getString(nameCol) ?: "unknown.mp4",
                    durationMs = cursor.getLong(durationCol),
                    sizeBytes = cursor.getLong(sizeCol)
                )
            }
        }
        return items
    }
}
