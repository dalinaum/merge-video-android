package com.example.mergevideo.data

import android.content.ContentValues
import android.content.Context
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import java.io.File
import java.io.FileDescriptor
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MergedVideoSink(private val context: Context) {

    fun create(): Sink {
        val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val displayName = "merged_$timestamp.mp4"
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            createWithMediaStore(displayName)
        } else {
            createLegacy(displayName)
        }
    }

    private fun createWithMediaStore(displayName: String): Sink {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            put(MediaStore.Video.Media.RELATIVE_PATH, "DCIM/Camera")
            put(MediaStore.Video.Media.IS_PENDING, 1)
        }
        val collection = MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
        val uri = context.contentResolver.insert(collection, values)
            ?: error("Failed to insert MediaStore entry.")
        val pfd = context.contentResolver.openFileDescriptor(uri, "rw")
            ?: run {
                context.contentResolver.delete(uri, null, null)
                error("Failed to open output descriptor.")
            }
        return PendingMediaStoreSink(context, uri, displayName, pfd)
    }

    @Suppress("DEPRECATION")
    private fun createLegacy(displayName: String): Sink {
        val cameraDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera")
        if (!cameraDir.exists()) cameraDir.mkdirs()
        val outFile = File(cameraDir, displayName)
        val pfd = ParcelFileDescriptor.open(
            outFile,
            ParcelFileDescriptor.MODE_READ_WRITE or ParcelFileDescriptor.MODE_CREATE
        )
        return LegacyFileSink(context, outFile, displayName, pfd)
    }

    interface Sink : AutoCloseable {
        val uri: Uri
        val displayName: String
        val fileDescriptor: FileDescriptor
        fun commit()
    }

    private class PendingMediaStoreSink(
        private val context: Context,
        override val uri: Uri,
        override val displayName: String,
        private val pfd: ParcelFileDescriptor
    ) : Sink {
        override val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor
        private var state = State.OPEN

        override fun commit() {
            if (state != State.OPEN) return
            pfd.close()
            val finalize = ContentValues().apply { put(MediaStore.Video.Media.IS_PENDING, 0) }
            context.contentResolver.update(uri, finalize, null, null)
            state = State.COMMITTED
        }

        override fun close() {
            if (state != State.OPEN) return
            runCatching { pfd.close() }
            runCatching { context.contentResolver.delete(uri, null, null) }
            state = State.ABORTED
        }
    }

    private class LegacyFileSink(
        private val context: Context,
        private val file: File,
        override val displayName: String,
        private val pfd: ParcelFileDescriptor
    ) : Sink {
        override val uri: Uri get() = Uri.fromFile(file)
        override val fileDescriptor: FileDescriptor get() = pfd.fileDescriptor
        private var state = State.OPEN

        override fun commit() {
            if (state != State.OPEN) return
            pfd.close()
            MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("video/mp4"), null)
            state = State.COMMITTED
        }

        override fun close() {
            if (state != State.OPEN) return
            runCatching { pfd.close() }
            runCatching { file.delete() }
            state = State.ABORTED
        }
    }

    private enum class State { OPEN, COMMITTED, ABORTED }
}
