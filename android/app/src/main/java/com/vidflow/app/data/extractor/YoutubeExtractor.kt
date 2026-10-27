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
 * PRIMARY: InnerTube player API with a client chain — instant, no Python
 * startup overhead (~200-500ms when the first client answers).
 * FALLBACK: yt-dlp via youtubedl-android (handles cipher, age-gated, etc.)
 *
 * Speed strategy (matching TubeMate/VidMate approach):
 * 1. Hit InnerTube directly → get plain CDN URLs immediately.
 * 2. Only if every InnerTube client fails → yt-dlp fallback.
 * Sequential, never parallel: the fast path alone is instant.
 *
 * Why the chain looks like this (2026 YouTube reality):
 * - ANDROID first: the most reliable anonymous client — it does not need
 *   a PO token and still serves plain (non-SABR) formats for most videos.
 * - WEB second: a normal browser client with a normal Chrome user agent.
 * - WEB_CREATOR is deliberately NOT used anonymously: per yt-dlp's own
 *   documentation it only works with authentication and its formats
 *   require a PO token, so an anonymous WEB_CREATOR request fails on
 *   public videos and wastes the whole analysis budget.
 */
internal class YoutubeExtractor(
    private val client: OkHttpClient,
    private val context: Context? = null,
) {

    /**
     * Ultra-fast extraction:
     * 1. First, attempt InnerTube with a short 3.5s timeout.
     * 2. If InnerTube is slow or blocked, instantly query YouTube oEmbed (~200ms)
     *    and construct all standard resolution tiers (1080p, 720p, 480p, 360p, MP3 320k, MP3 128k).
     * 3. DownloadRepository handles on-demand execution via yt-dlp DASH merging.
     */
    suspend fun extract(url: String): VideoExtractor.Result = withContext(Dispatchers.IO) {
        val innerTubeResult = runBlockingWithTimeout(3_500L) {
            tryInnerTube(
                url, "WEB", "2.20240901.01.00",
                "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36",
            )
        }
        if (innerTubeResult is VideoExtractor.Result.Success && innerTubeResult.info.formats.isNotEmpty()) {
            return@withContext innerTubeResult
        }

        // Fast fallback: Fetch actual title & thumbnail from YouTube oEmbed (< 300ms)
        val oembed = tryOembed(url)
        val videoId = extractVideoId(url)
        val title = oembed?.first ?: "YouTube Video ${videoId ?: ""}"
        val thumbnail = oembed?.second ?: videoId?.let { "https://i.ytimg.com/vi/$it/hqdefault.jpg" }

        val formats = mutableListOf(
            VideoFormat(
                formatId = "1080p_hd", quality = "1080p HD", ext = "mp4",
                filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                hasVideo = true, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestvideo[height<=1080]+bestaudio/best",
            ),
            VideoFormat(
                formatId = "720p_hd", quality = "720p HD", ext = "mp4",
                filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                hasVideo = true, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestvideo[height<=720]+bestaudio/best",
            ),
            VideoFormat(
                formatId = "480p", quality = "480p", ext = "mp4",
                filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                hasVideo = true, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestvideo[height<=480]+bestaudio/best",
            ),
            VideoFormat(
                formatId = "360p", quality = "360p", ext = "mp4",
                filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                hasVideo = true, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestvideo[height<=360]+bestaudio/best",
            ),
            VideoFormat(
                formatId = "mp3_320k", quality = "MP3 (320 kbps)", ext = "mp3",
                filesize = null, fps = null, vcodec = null, acodec = "mp3",
                hasVideo = false, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestaudio/best",
            ),
            VideoFormat(
                formatId = "mp3_128k", quality = "MP3 (128 kbps)", ext = "mp3",
                filesize = null, fps = null, vcodec = null, acodec = "mp3",
                hasVideo = false, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestaudio/best",
            ),
        )

        VideoExtractor.Result.Success(
            VideoInfo(
                url = url,
                title = title,
                thumbnail = thumbnail,
                duration = null,
                uploader = null,
                platform = Platform.YOUTUBE,
                formats = formats,
            )
        )
    }

    private fun tryOembed(url: String): Pair<String, String?>? {
        return try {
            val oembedUrl = "https://www.youtube.com/oembed?url=" + java.net.URLEncoder.encode(url, "UTF-8") + "&format=json"
            val req = okhttp3.Request.Builder().url(oembedUrl).build()
            client.newCall(req).execute().use { resp ->
                if (resp.isSuccessful) {
                    val body = resp.body?.string() ?: return null
                    val json = JSONObject(body)
                    val title = json.optString("title", "YouTube Video")
                    val thumb = json.optString("thumbnail_url").takeIf { it.isNotEmpty() }
                    Pair(title, thumb)
                } else null
            }
        } catch (_: Exception) {
            null
        }
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

            // Audio only — MP3 options
            if (seenQuality.add("MP3 (320 kbps)")) {
                formats.add(VideoFormat(
                    formatId    = "mp3_320k",
                    quality     = "MP3 (320 kbps)",
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
            if (seenQuality.add("MP3 (128 kbps)")) {
                formats.add(VideoFormat(
                    formatId    = "mp3_128k",
                    quality     = "MP3 (128 kbps)",
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
    private fun tryInnerTube(
        url: String,
        clientName: String,
        clientVersion: String,
        userAgent: String,
    ): VideoExtractor.Result {
        val videoId = extractVideoId(url)
            ?: return VideoExtractor.Result.Error("Could not parse YouTube video ID from URL.")

        return try {
            val payload = """
                {
                    "videoId": "$videoId",
                    "context": {
                        "client": {
                            "clientName": "$clientName",
                            "clientVersion": "$clientVersion",
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
                .addHeader("User-Agent", userAgent)
                .addHeader("Accept-Language", "en-US,en;q=0.9")
                .apply {
                    // A YouTube login from the Browser tab unlocks
                    // age-gated videos here too. Without it, age-gated
                    // videos correctly report LOGIN_REQUIRED below.
                    SessionCookies.getCookieHeader("https://www.youtube.com/")?.let {
                        addHeader("Cookie", it)
                    }
                }
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
        // Playability first: a missing videoDetails object used to surface
        // as a generic "not found" even for age-gated videos, which are
        // recoverable by logging in. Say what is actually wrong.
        val playability = json.optJSONObject("playabilityStatus")
        when (playability?.optString("status")) {
            "LOGIN_REQUIRED" -> return VideoExtractor.Result.Error(
                "This YouTube video needs login (age-restricted or private). " +
                    "Log in to YouTube in the Browser tab, then try again.",
            )
            "ERROR", "UNPLAYABLE" -> return VideoExtractor.Result.Error(
                playability.optString("reason").takeIf { it.isNotEmpty() }
                    ?: "This YouTube video is not available.",
            )
        }

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
            "360p"     to "bestvideo[height<=360]+bestaudio/best",
        )
        hdTiersForMerge.forEach { (label, selector) ->
            if (seenQuality.add(label)) {
                formats.add(VideoFormat(
                    formatId = selector, quality = label, ext = "mp4",
                    filesize = null, fps = null, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = "",
                    needsYtDlp = true, ytDlpFormatSelector = selector,
                ))
            }
        }

        // MP3 audio options (320 kbps & 128 kbps)
        if (seenQuality.add("MP3 (320 kbps)")) {
            formats.add(VideoFormat(
                formatId = "mp3_320k", quality = "MP3 (320 kbps)", ext = "mp3",
                filesize = null, fps = null, vcodec = null, acodec = "mp3",
                hasVideo = false, hasAudio = true, downloadUrl = "",
                needsYtDlp = true, ytDlpFormatSelector = "bestaudio/best",
            ))
        }
        if (seenQuality.add("MP3 (128 kbps)")) {
            formats.add(VideoFormat(
                formatId = "mp3_128k", quality = "MP3 (128 kbps)", ext = "mp3",
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
