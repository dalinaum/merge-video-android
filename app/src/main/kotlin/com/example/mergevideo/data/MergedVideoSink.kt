package com.example.mergevideo.data

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MergedVideoSink(private val context: Context) {

    data class Sink(val uri: Uri, val displayName: String, val fd: FileDescriptor, val close: () -> Unit)

    fun create(): Sink {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "merged_$timestamp.mp4"

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
                put(MediaStore.Video.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = context.contentResolver.insert(collection, values)
                ?: error("MediaStore에 항목을 만들 수 없습니다.")

            val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
                ?: error("출력 파일을 열 수 없습니다.")
            return Sink(
                uri = uri,
                displayName = displayName,
                fd = pfd.fileDescriptor,
                close = {
                    pfd.close()
                    val finalize = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
                    context.contentResolver.update(uri, finalize, null, null)
                }
            )
        }

        @Suppress("DEPRECATION")
        val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!cameraDir.exists()) cameraDir.mkdirs()
        val outFile = File(cameraDir, displayName)

        @Suppress("DEPRECATION")
        val pfd = android.os.ParcelFileDescriptor.open(outFile, android.os.ParcelFileDescriptor.MODE_READ_WRITE or android.os.ParcelFileDescriptor.MODE_CREATE)
        val uri = Uri.fromFile(outFile)

        return Sink(
            uri = uri,
            displayName = displayName,
            fd = pfd.fileDescriptor,
            close = {
                pfd.close()
                val values = ContentValues().apply {
                    put(MediaStore.Video.Media.DATA, outFile.absolutePath)
                    put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
                    put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
                }
                context.contentResolver.insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, values)
            }
        )
    }
}
