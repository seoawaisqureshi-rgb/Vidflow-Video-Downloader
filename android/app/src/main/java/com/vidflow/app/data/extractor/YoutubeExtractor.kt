package com.vidflow.app.data.extractor

import android.content.Context
import android.util.Log
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import com.vidflow.app.data.session.SessionCookies
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject

/**
 * YouTube extractor.
 * PRIMARY: InnerTube WEB_CREATOR API — instant, no Python startup overhead (~200-500ms)
 * FALLBACK: yt-dlp via youtubedl-android (handles cipher, age-gated, etc.)
 *
 * Speed strategy (matching TubeMate/VidMate approach):
 * 1. Hit InnerTube API directly → get plain CDN URLs immediately
 * 2. Only if InnerTube fails (ciphered/age-gated) → yt-dlp fallback
 * Sequential, never parallel: the fast path alone is instant.
 */
internal class YoutubeExtractor(
    private val client: OkHttpClient,
    private val context: Context? = null,
) {

    /**
     * Ordered chain (brief 3.4): the InnerTube fast path runs FIRST, alone,
     * with a 15s timeout. Only if it fails does yt-dlp run, with a 30s
     * timeout. No parallel run, no 2.5s delayed-fallback timer: a timer is
     * not an architecture, and racing two extractors doubled CPU and
     * network use on every YouTube link.
     */
    suspend fun extract(url: String): VideoExtractor.Result = withContext(Dispatchers.IO) {
        val inner = runBlockingWithTimeout(15_000L) { tryInnerTube(url) }
        if (inner is VideoExtractor.Result.Success && inner.info.formats.isNotEmpty()) {
            return@withContext inner
        }

        val ytdlp = if (context != null) {
            runBlockingWithTimeout(30_000L) { tryYtDlp(url, context) }
        } else {
            null
        }
        if (ytdlp is VideoExtractor.Result.Success && ytdlp.info.formats.isNotEmpty()) {
            return@withContext ytdlp
        }

        inner ?: VideoExtractor.Result.Error("YouTube extraction failed. Check your internet connection and try again.")
    }

    // ── yt-dlp ───────────────────────────────────────────────────────────────
    private fun tryYtDlp(url: String, context: Context): VideoExtractor.Result? {
        return try {
            val request = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--socket-timeout", "20")
                addOption("--extractor-retries", "2")
                SessionCookies.writeCookiesTxt(context, Platform.YOUTUBE)?.let { cookies ->
                    addOption("--cookies", cookies.absolutePath)
                }
            }
            val response = YoutubeDL.getInstance().getInfo(request)

            val title     = response.title     ?: "YouTube Video"
            val thumbnail = response.thumbnail
            val duration  = response.duration?.toLong()
            val uploader  = response.uploader

            val allFormats = response.formats ?: return null
            val seenQuality = mutableSetOf<String>()
            val formats = mutableListOf<VideoFormat>()

            // HD tiers — merged via yt-dlp + FFmpeg at download time
            val hdTiers = listOf(
                Triple("4320", "4320p (8K)", "bestvideo[height=4320]+bestaudio/best"),
                Triple("2160", "2160p (4K)", "bestvideo[height=2160]+bestaudio/best"),
                Triple("1440", "1440p HD",   "bestvideo[height=1440]+bestaudio/best"),
                Triple("1080", "1080p HD",   "bestvideo[height<=1080]+bestaudio/best"),
                Triple("720",  "720p HD",    "bestvideo[height<=720]+bestaudio/best"),
                Triple("480",  "480p",       "bestvideo[height<=480]+bestaudio/best"),
            )
            hdTiers.forEach { (heightKey, label, selector) ->
                val available = allFormats.any { f ->
                    val h = f.height ?: 0
                    h > 0 && h.toString().startsWith(heightKey) &&
                    (f.vcodec ?: "none") != "none"
                }
                if (available && seenQuality.add(label)) {
                    formats.add(VideoFormat(
                        formatId    = selector,
                        quality     = label,
                        ext         = "mp4",
                        filesize    = null,
                        fps         = null,
                        vcodec      = "h264",
                        acodec      = "aac",
                        hasVideo    = true,
                        hasAudio    = true,
                        downloadUrl = "",
                        needsYtDlp  = true,
                        ytDlpFormatSelector = selector,
                    ))
                }
            }

            // Muxed streams — direct OkHttp download
            allFormats.forEach { f ->
                val dlUrl = f.url?.takeIf { it.isNotEmpty() } ?: return@forEach
                val hasV  = (f.vcodec ?: "none") != "none"
                val hasA  = (f.acodec ?: "none") != "none"
                if (!hasV || !hasA) return@forEach
                val rawQ  = f.formatNote?.takeIf { it.isNotEmpty() }
                    ?: f.format?.takeIf { it.isNotEmpty() }
                    ?: "Combined"
                if (seenQuality.add(rawQ)) {
                    formats.add(VideoFormat(
                        formatId    = f.formatId ?: "",
                        quality     = rawQ,
                        ext         = f.ext ?: "mp4",
                        filesize    = null,
                        fps         = f.fps?.toInt(),
                        vcodec      = f.vcodec,
                        acodec      = f.acodec,
                        hasVideo    = true,
                        hasAudio    = true,
                        downloadUrl = dlUrl,
                    ))
                }
            }

            // Audio only — MP3
            val audioFmt = allFormats.firstOrNull { f ->
                (f.acodec ?: "none") != "none" && (f.vcodec ?: "none") == "none" && !f.url.isNullOrEmpty()
            }
            if (audioFmt != null && seenQuality.add("MP3 (Audio Only)")) {
                formats.add(VideoFormat(
                    formatId    = "bestaudio/best",
                    quality     = "MP3 (Audio Only)",
                    ext         = "mp3",
                    filesize    = null,
                    fps         = null,
                    vcodec      = null,
                    acodec      = "mp3",
                    hasVideo    = false,
                    hasAudio    = true,
                    downloadUrl = "",
                    needsYtDlp  = true,
                    ytDlpFormatSelector = "bestaudio/best",
                ))
            }

            if (formats.isEmpty()) return null

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumbnail,
                duration = duration, uploader = uploader,
                platform = Platform.YOUTUBE,
                formats = formats,
            ))
        } catch (e: Exception) {
            Log.w("VidFlow/YT-ytdlp", "yt-dlp failed: ${e.message}")
            null
        }
    }

    // ── InnerTube API (fast path) ─────────────────────────────────────────────
    private fun tryInnerTube(url: String): VideoExtractor.Result {
        val videoId = extractVideoId(url)
            ?: return VideoExtractor.Result.Error("Could not parse YouTube video ID from URL.")

        return try {
            // WEB_CREATOR client returns plain (non-ciphered) stream URLs for most public videos
            val payload = """
                {
                    "videoId": "$videoId",
                    "context": {
                        "client": {
                            "clientName": "WEB_CREATOR",
                            "clientVersion": "1.20240101.01.00",
                            "hl": "en",
                            "gl": "US"
                        }
                    }
                }
            """.trimIndent()

            val reqBody = payload.toRequestBody("application/json; charset=utf-8".toMediaTypeOrNull())
            val request = okhttp3.Request.Builder()
                .url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .post(reqBody)
                .addHeader("Content-Type", "application/json")
                .addHeader("User-Agent", "Mozilla/5.0 (compatible; Googlebot/2.1)")
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .build()

            val json = client.newCall(request).execute().use { resp ->
                if (resp.isSuccessful) resp.body?.string()?.let { JSONObject(it) } else null
            } ?: return VideoExtractor.Result.Error("Could not reach YouTube. Check your internet connection.")

            parseInnerTubeResponse(url, json)
        } catch (e: Exception) {
            VideoExtractor.Result.Error("YouTube extraction failed: ${e.message}")
        }
    }

    private fun extractVideoId(url: String): String? {
        url.regexFind("""[?&]v=([a-zA-Z0-9_-]{11})""")?.let { return it }
        url.regexFind("""/shorts/([a-zA-Z0-9_-]{11})""")?.let { return it }
        url.regexFind("""youtu\.be/([a-zA-Z0-9_-]{11})""")?.let { return it }
        url.regexFind("""/embed/([a-zA-Z0-9_-]{11})""")?.let { return it }
        return null
    }

    private fun parseInnerTubeResponse(url: String, json: JSONObject): VideoExtractor.Result {
        val details = json.optJSONObject("videoDetails")
            ?: return VideoExtractor.Result.Error("Video not found or private.")

        val title     = details.optString("title", "YouTube Video")
        val author    = details.optString("author", "")
        val duration  = details.optString("lengthSeconds").toLongOrNull()
        val thumbnail = details.optJSONObject("thumbnail")
            ?.optJSONArray("thumbnails")
            ?.let { arr ->
                (arr.length() - 1 downTo 0).map { arr.getJSONObject(it) }
                    .firstOrNull { it.optInt("width", 0) >= 320 }
                    ?.optString("url")
            }

        val streamingData = json.optJSONObject("streamingData")
            ?: return VideoExtractor.Result.Error("No streaming data found. Video may be age-restricted or unavailable.")

        val formats = mutableListOf<VideoFormat>()
        val seenQuality = mutableSetOf<String>()

        // Parse muxed (combined audio+video) formats
        streamingData.optJSONArray("formats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val dlUrl = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                val quality = f.optString("qualityLabel", f.optString("quality", "Unknown"))
                val ext = f.optString("mimeType", "video/mp4").substringAfter("/").substringBefore(";")
                if (seenQuality.add(quality)) {
                    formats.add(VideoFormat(
                        formatId = f.optString("itag"), quality = quality,
                        ext = ext.ifEmpty { "mp4" },
                        filesize = f.optLong("contentLength").takeIf { it > 0 },
                        fps = f.optInt("fps").takeIf { it > 0 },
                        vcodec = "h264", acodec = "aac",
                        hasVideo = true, hasAudio = true, downloadUrl = dlUrl,
                    ))
                }
            }
        }

        // Also check adaptiveFormats for any combined streams
        streamingData.optJSONArray("adaptiveFormats")?.let { arr ->
            for (i in 0 until arr.length()) {
                val f = arr.getJSONObject(i)
                val dlUrl = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                val mimeType = f.optString("mimeType", "")
                val hasV = mimeType.startsWith("video/")
                val hasA = mimeType.startsWith("audio/")
                // Only add combined streams from adaptive
                if (!hasV || hasA) continue
                val quality = f.optString("qualityLabel").takeIf { it.isNotEmpty() } ?: continue
                // Skip if already covered by muxed formats — adaptive video-only needs merge
            }
        }

        // Add HD tiers that need yt-dlp merge (if context is present for yt-dlp)
        // Always add these as options — DownloadRepository will route them to yt-dlp
        val hdTiersForMerge = listOf(
            "1080p HD" to "bestvideo[height<=1080]+bestaudio/best",
            "720p HD"  to "bestvideo[height<=720]+bestaudio/best",
            "480p"     to "bestvideo[height<=480]+bestaudio/best",
        )
        hdTiersForMerge.forEach { (label, selector) ->
            if (seenQuality.add("$label (yt-dlp)")) {
                formats.add(VideoFormat(
                    formatId = selector, quality = label, ext = "mp4",
                    filesize = null, fps = null, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = "",
                    needsYtDlp = true, ytDlpFormatSelector = selector,
                ))
            }
        }

        // MP3 audio only option
        if (seenQuality.add("MP3 (Audio Only)")) {
            formats.add(VideoFormat(
                formatId = "bestaudio/best", quality = "MP3 (Audio Only)", ext = "mp3",
                filesize = null, fps = null, vcodec = null, acodec = "mp3",
                hasVideo = false, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestaudio/best",
            ))
        }

        if (formats.isEmpty()) {
            return VideoExtractor.Result.Error(
                "YouTube stream extraction failed.\n\nYouTube encrypts video streams. " +
                "Please wait for app to finish downloading yt-dlp on first launch, then try again."
            )
        }

        return VideoExtractor.Result.Success(VideoInfo(
            url = url, title = title, thumbnail = thumbnail, duration = duration,
            uploader = author, platform = Platform.YOUTUBE,
            formats = formats.sortedWith(compareByDescending<VideoFormat> {
                it.quality.removeSuffix("p").substringBefore(" ").toIntOrNull() ?: 0
            }.thenBy { if (it.needsYtDlp) 1 else 0 }),
        ))
    }
}
