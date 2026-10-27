package com.vidflow.app.data.downloader

import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.util.Log
import java.io.File

/**
 * Merges a separate video-only file and audio-only file into one MP4,
 * on-device, without re-encoding, using only Android framework APIs
 * (MediaExtractor + MediaMuxer).
 *
 * Why this exists (brief section 3.2): the old code let yt-dlp merge DASH
 * streams internally, and its merge phase reported no progress, so the UI
 * showed 0 KB/s. This is the separate merge step: it copies samples from
 * both inputs into one output and reports real progress as
 * muxed presentation time / total duration, so the UI can show a genuine
 * "Merging..." state with a percent instead of a speed.
 *
 * Returns the merged file, or null when the inputs cannot be muxed this way
 * (unsupported codecs/containers, e.g. VP9-in-WebM). Callers fall back to
 * yt-dlp's own merge in that case; nothing is lost, the inputs are kept.
 */
object MediaMuxerMerger {

    private const val TAG = "VidFlow/Muxer"

    /**
     * @param onProgress percent 0..100 of the merge, from sample timestamps.
     *   Never a speed: merging is local work, not a download.
     */
    fun merge(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: (percent: Int) -> Unit,
    ): File? {
        if (!videoFile.exists() || !audioFile.exists()) return null
        var muxer: MediaMuxer? = null
        try {
            val videoExtractor = MediaExtractor().apply { setDataSource(videoFile.absolutePath) }
            val audioExtractor = MediaExtractor().apply { setDataSource(audioFile.absolutePath) }
            try {
                val videoTrack = selectTrack(videoExtractor, "video/")
                    ?: run { Log.w(TAG, "No video track in ${videoFile.name}"); return null }
                val audioTrack = selectTrack(audioExtractor, "audio/")
                    ?: run { Log.w(TAG, "No audio track in ${audioFile.name}"); return null }
                val videoFormat = videoExtractor.getTrackFormat(videoTrack)
                val audioFormat = audioExtractor.getTrackFormat(audioTrack)
                // MediaMuxer MP4 output only accepts AVC/HEVC video + AAC audio.
                val videoMime = videoFormat.getString(MediaFormat.KEY_MIME) ?: ""
                val audioMime = audioFormat.getString(MediaFormat.KEY_MIME) ?: ""
                if (videoMime !in listOf("video/avc", "video/hevc")) {
                    Log.w(TAG, "Unsupported video mime for muxing: $videoMime")
                    return null
                }
                if (audioMime != "audio/mp4a-latm") {
                    Log.w(TAG, "Unsupported audio mime for muxing: $audioMime")
                    return null
                }
                val durationUs = maxOf(
                    videoFormat.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L,
                    audioFormat.getLongOrNull(MediaFormat.KEY_DURATION) ?: 0L,
                )
                if (outputFile.exists()) outputFile.delete()
                muxer = MediaMuxer(outputFile.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
                val outVideo = muxer.addTrack(videoFormat)
                val outAudio = muxer.addTrack(audioFormat)
                muxer.start()
                var lastPercent = -1
                fun report(presentationUs: Long) {
                    if (durationUs <= 0) return
                    val pct = ((presentationUs * 100) / durationUs).toInt().coerceIn(0, 99)
                    if (pct != lastPercent) {
                        lastPercent = pct
                        onProgress(pct)
                    }
                }
                copyTrack(videoExtractor, videoTrack, muxer, outVideo, ::report)
                copyTrack(audioExtractor, audioTrack, muxer, outAudio, ::report)
                onProgress(99)
                return outputFile
            } finally {
                runCatching { videoExtractor.release() }
                runCatching { audioExtractor.release() }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Mux failed, caller falls back to yt-dlp merge: ${e.message}")
            runCatching { outputFile.delete() }
            return null
        } finally {
            try {
                muxer?.stop()
            } catch (_: Exception) {
            }
            runCatching { muxer?.release() }
        }
    }

    private fun selectTrack(extractor: MediaExtractor, mimePrefix: String): Int? {
        for (i in 0 until extractor.trackCount) {
            val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME) ?: ""
            if (mime.startsWith(mimePrefix)) return i
        }
        return null
    }

    private fun copyTrack(
        extractor: MediaExtractor,
        track: Int,
        muxer: MediaMuxer,
        outTrack: Int,
        report: (presentationUs: Long) -> Unit,
    ) {
        extractor.selectTrack(track)
        val buffer = java.nio.ByteBuffer.allocate(1024 * 512)
        val info = android.media.MediaCodec.BufferInfo()
        while (true) {
            info.offset = 0
            info.size = extractor.readSampleData(buffer, 0)
            if (info.size < 0) break
            info.presentationTimeUs = extractor.sampleTime
            info.flags = extractor.sampleFlags
            muxer.writeSampleData(outTrack, buffer, info)
            report(extractor.sampleTime)
            extractor.advance()
        }
        extractor.unselectTrack(track)
    }

    private fun MediaFormat.getLongOrNull(key: String): Long? = try {
        getLong(key)
    } catch (_: Exception) {
        null
    }
}
