package com.example.mergevideo.merge

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileDescriptor
import java.nio.ByteBuffer
import kotlin.math.max

class VideoMerger(private val context: Context) {

    fun interface ProgressListener {
        fun onProgress(currentIndex: Int, total: Int)
    }

    fun merge(inputs: List<Uri>, outputFd: FileDescriptor, progress: ProgressListener? = null) {
        require(inputs.size >= 2) { "Need at least 2 inputs to merge." }

        val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var ptsOffsetUs = 0L
        var bufferCapacity = INITIAL_BUFFER_SIZE
        var buffer = ByteBuffer.allocate(bufferCapacity)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            inputs.forEachIndexed { index, uri ->
                progress?.onProgress(index, inputs.size)

                val extractor = openExtractor(uri)
                try {
                    val videoSrcIdx = findTrackIndex(extractor, "video/")
                    val audioSrcIdx = findTrackIndex(extractor, "audio/")
                    if (videoSrcIdx < 0 && index == 0) error("First input has no video track.")

                    if (!muxerStarted) {
                        videoTrackIndex = muxer.addTrack(extractor.getTrackFormat(videoSrcIdx))
                        if (audioSrcIdx >= 0) {
                            audioTrackIndex = muxer.addTrack(extractor.getTrackFormat(audioSrcIdx))
                        }
                        muxer.start()
                        muxerStarted = true
                    }

                    val maxInputSize = listOf(videoSrcIdx, audioSrcIdx)
                        .filter { it >= 0 }
                        .maxOf { extractor.getTrackFormat(it).getIntegerOrNull(MediaFormat.KEY_MAX_INPUT_SIZE) ?: 0 }
                    if (maxInputSize > bufferCapacity) {
                        bufferCapacity = maxInputSize
                        buffer = ByteBuffer.allocate(bufferCapacity)
                    }

                    var maxClipPtsUs = 0L

                    if (videoSrcIdx >= 0 && videoTrackIndex >= 0) {
                        val (lastPts, frameDur) = writeSamples(
                            extractor, videoSrcIdx, muxer, videoTrackIndex,
                            ptsOffsetUs, buffer, bufferInfo
                        )
                        maxClipPtsUs = max(maxClipPtsUs, lastPts + frameDur)
                    }

                    if (audioSrcIdx >= 0 && audioTrackIndex >= 0) {
                        val (lastPts, frameDur) = writeSamples(
                            extractor, audioSrcIdx, muxer, audioTrackIndex,
                            ptsOffsetUs, buffer, bufferInfo
                        )
                        maxClipPtsUs = max(maxClipPtsUs, lastPts + frameDur)
                    }

                    ptsOffsetUs += maxClipPtsUs
                } finally {
                    extractor.release()
                }
            }
            progress?.onProgress(inputs.size, inputs.size)
        } finally {
            if (muxerStarted) runCatching { muxer.stop() }
            runCatching { muxer.release() }
        }
    }

    private fun openExtractor(uri: Uri): MediaExtractor {
        val extractor = MediaExtractor()
        try {
            val pfd = context.contentResolver.openFileDescriptor(uri, "r")
                ?: error("Cannot open input: $uri")
            pfd.use { extractor.setDataSource(it.fileDescriptor) }
            return extractor
        } catch (t: Throwable) {
            extractor.release()
            throw t
        }
    }

    private fun findTrackIndex(extractor: MediaExtractor, mimePrefix: String): Int {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: continue
            if (mime.startsWith(mimePrefix)) return i
        }
        return -1
    }

    private fun writeSamples(
        extractor: MediaExtractor,
        srcTrackIndex: Int,
        muxer: MediaMuxer,
        dstTrackIndex: Int,
        ptsOffsetUs: Long,
        buffer: ByteBuffer,
        bufferInfo: MediaCodec.BufferInfo
    ): Pair<Long, Long> {
        extractor.selectTrack(srcTrackIndex)

        var lastPtsUs = 0L
        var prevPtsUs = -1L
        var minDeltaUs = Long.MAX_VALUE

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTimeUs = extractor.sampleTime
            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = sampleTimeUs + ptsOffsetUs
            bufferInfo.flags = extractor.sampleFlags

            muxer.writeSampleData(dstTrackIndex, buffer, bufferInfo)

            if (prevPtsUs >= 0) {
                val delta = sampleTimeUs - prevPtsUs
                if (delta in 1 until minDeltaUs) minDeltaUs = delta
            }
            prevPtsUs = sampleTimeUs
            lastPtsUs = sampleTimeUs

            extractor.advance()
        }
        extractor.unselectTrack(srcTrackIndex)

        val frameDurationUs = if (minDeltaUs == Long.MAX_VALUE) DEFAULT_FRAME_DURATION_US else minDeltaUs
        return lastPtsUs to frameDurationUs
    }

    private fun MediaFormat.getIntegerOrNull(key: String): Int? =
        if (containsKey(key)) getInteger(key) else null

    companion object {
        private const val INITIAL_BUFFER_SIZE = 1 * 1024 * 1024
        private const val DEFAULT_FRAME_DURATION_US = 33_333L
    }
}
