package com.example.mergevideo.merge

import android.content.Context
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import java.io.FileDescriptor
import java.nio.ByteBuffer

class VideoMerger(private val context: Context) {

    fun interface ProgressListener {
        fun onProgress(currentIndex: Int, total: Int)
    }

    fun merge(inputs: List<Uri>, outputFd: FileDescriptor, progress: ProgressListener? = null) {
        require(inputs.size >= 2) { "병합하려면 최소 2개의 파일이 필요합니다." }

        val muxer = MediaMuxer(outputFd, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        var muxerStarted = false

        var videoTrackIndex = -1
        var audioTrackIndex = -1
        var videoPtsOffsetUs = 0L
        var audioPtsOffsetUs = 0L

        val buffer = ByteBuffer.allocate(MAX_SAMPLE_SIZE)
        val bufferInfo = MediaCodec.BufferInfo()

        try {
            inputs.forEachIndexed { index, uri ->
                progress?.onProgress(index, inputs.size)

                val extractor = MediaExtractor()
                context.contentResolver.openFileDescriptor(uri, "r")?.use { pfd ->
                    extractor.setDataSource(pfd.fileDescriptor)
                }

                val videoSrcIdx = findTrackIndex(extractor, "video/")
                val audioSrcIdx = findTrackIndex(extractor, "audio/")

                if (!muxerStarted) {
                    if (videoSrcIdx < 0) error("첫 번째 파일에 비디오 트랙이 없습니다.")
                    val videoFormat = extractor.getTrackFormat(videoSrcIdx)
                    videoTrackIndex = muxer.addTrack(videoFormat)

                    if (audioSrcIdx >= 0) {
                        val audioFormat = extractor.getTrackFormat(audioSrcIdx)
                        audioTrackIndex = muxer.addTrack(audioFormat)
                    }
                    muxer.start()
                    muxerStarted = true
                }

                var maxVideoPtsUs = 0L
                var maxAudioPtsUs = 0L

                if (videoSrcIdx >= 0 && videoTrackIndex >= 0) {
                    val (lastPts, frameDur) = writeSamples(
                        extractor = extractor,
                        srcTrackIndex = videoSrcIdx,
                        muxer = muxer,
                        dstTrackIndex = videoTrackIndex,
                        ptsOffsetUs = videoPtsOffsetUs,
                        buffer = buffer,
                        bufferInfo = bufferInfo
                    )
                    maxVideoPtsUs = lastPts + frameDur
                }

                if (audioSrcIdx >= 0 && audioTrackIndex >= 0) {
                    val (lastPts, frameDur) = writeSamples(
                        extractor = extractor,
                        srcTrackIndex = audioSrcIdx,
                        muxer = muxer,
                        dstTrackIndex = audioTrackIndex,
                        ptsOffsetUs = audioPtsOffsetUs,
                        buffer = buffer,
                        bufferInfo = bufferInfo
                    )
                    maxAudioPtsUs = lastPts + frameDur
                }

                videoPtsOffsetUs += maxVideoPtsUs
                audioPtsOffsetUs += maxAudioPtsUs

                extractor.release()
            }
            progress?.onProgress(inputs.size, inputs.size)
        } finally {
            if (muxerStarted) {
                runCatching { muxer.stop() }
            }
            runCatching { muxer.release() }
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
        extractor.seekTo(0L, MediaExtractor.SEEK_TO_CLOSEST_SYNC)

        var lastPtsUs = 0L
        var prevPtsUs = -1L
        var minDeltaUs = Long.MAX_VALUE

        while (true) {
            buffer.clear()
            val sampleSize = extractor.readSampleData(buffer, 0)
            if (sampleSize < 0) break

            val sampleTimeUs = extractor.sampleTime
            val flags = extractor.sampleFlags

            bufferInfo.offset = 0
            bufferInfo.size = sampleSize
            bufferInfo.presentationTimeUs = sampleTimeUs + ptsOffsetUs
            bufferInfo.flags = flags

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

    companion object {
        private const val MAX_SAMPLE_SIZE = 4 * 1024 * 1024
        private const val DEFAULT_FRAME_DURATION_US = 33_333L
    }
}
