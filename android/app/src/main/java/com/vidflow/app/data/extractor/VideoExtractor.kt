package com.vidflow.app.data.extractor

import android.content.Context
import android.util.Log
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.selects.select
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.FormBody
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.regex.Pattern

/**
 * Master extractor. Routes each URL to the best strategy.
 * Design philosophy: show ONLY clean, user-friendly formats.
 * - NEVER show "video only" or raw DASH stream options to user
 * - Facebook: HD / SD with audio+video merged automatically
 * - YouTube: quality tiers (1080p HD, 720p HD, 480p, 360p) + MP3
 * - Instagram: multiple HTTP approaches + yt-dlp fallback
 * - Threads: page scraping + yt-dlp fallback
 */
class VideoExtractor(
    private val client: OkHttpClient,
    private val context: Context? = null,
) {
    sealed class Result {
        data class Success(val info: VideoInfo) : Result()
        data class Error(val message: String, val recoverable: Boolean = false) : Result()
    }

    suspend fun extract(rawUrl: String): Result {
        val url = normalizeUrl(rawUrl.trim())
        return when (Platform.fromUrl(url)) {
            Platform.YOUTUBE     -> YoutubeExtractor(client, context).extract(url)
            Platform.TIKTOK      -> raceHttpAndYtDlp(url, Platform.TIKTOK) { TikTokExtractor(client).extract(url) }
            Platform.FACEBOOK    -> extractFacebook(url)
            Platform.INSTAGRAM   -> extractInstagram(url)
            Platform.THREADS     -> extractThreads(url)
            Platform.REDDIT      -> RedditExtractor(client).extract(url)
            Platform.TWITTER     -> TwitterExtractor(client).extract(url)
            Platform.VIMEO       -> VimeoExtractor(client).extract(url)
            Platform.DAILYMOTION -> DailymotionExtractor(client).extract(url)
            Platform.PINTEREST   -> raceHttpAndYtDlp(url, Platform.PINTEREST) { PinterestExtractor(client).extract(url) }
            Platform.UNKNOWN     -> {
                val clean = url.substringBefore("?").lowercase()
                if (clean.endsWith(".mp4") || clean.endsWith(".webm") ||
                    clean.endsWith(".mov") || clean.endsWith(".m4a") || clean.endsWith(".mp3")) {
                    GenericVideoExtractor().extract(url)
                } else {
                    context?.let { tryYtDlp(url, Platform.UNKNOWN) }
                        ?: Result.Error("Platform not supported. Paste a URL from YouTube, TikTok, Facebook, Instagram, Twitter/X, Reddit, Vimeo, Dailymotion, Pinterest, or Threads.")
                }
            }
        }
    }

    // ── Facebook ─────────────────────────────────────────────────────────────
    // Strategy: yt-dlp PRIMARY (gets clean HD/SD quality tiers with merged audio+video)
    // HTTP scraping SECONDARY (fast for public videos)
    // CRITICAL: Never show audio-only or video-only options to user.

    private suspend fun extractFacebook(url: String): Result {
        val lower = url.lowercase()
        if (lower.contains("/stories/") || lower.contains("story.php") || lower.contains("story_fbid")) {
            return Result.Error(
                "Facebook Stories cannot be downloaded — they expire after 24 hours and require login.\n\n" +
                "Only public Facebook post videos and Reels are supported."
            )
        }
        return raceHttpAndYtDlp(url, Platform.FACEBOOK) { FacebookExtractor(client).extract(url) }
    }

    // ── Instagram ─────────────────────────────────────────────────────────────
    // Try methods in order of reliability:
    // 1. HTTP scraping via InstagramExtractor + embed page (fast, no login)
    // 2. Instagram internal app API (app-like headers)
    // 3. yt-dlp with 'best' format selector (most reliable for public content)
    // NOTE: Never use specific format IDs for Instagram — causes 'requested format not available'

    private suspend fun extractInstagram(url: String): Result {
        if (url.contains("/stories/", ignoreCase = true)) {
            return Result.Error(
                "Instagram Stories expire after 24 hours and usually require login.\n\n" +
                "Try a public Reel or post link instead."
            )
        }
        val shortcode = url.regexFind("""/(p|reel|reels|tv)/([A-Za-z0-9_-]+)""", 2)
            ?: return Result.Error("Invalid Instagram URL. Please use a direct post, reel, or video link.")

        return raceHttpAndYtDlp(url, Platform.INSTAGRAM) {
            InstagramExtractor(client).extract(url)
                .takeIf { it is Result.Success }
                ?: tryInstagramAppApi(url, shortcode)
                ?: tryInstagramEmbed(url, shortcode)
                ?: Result.Error(
                    "Instagram requires login for this content.\n\n" +
                    "Try: Open in browser → tap share → copy link → paste here"
                )
        }
    }

    private fun tryInstagramAppApi(url: String, shortcode: String): Result? {
        return try {
            // Use Instagram's internal API endpoint with app-like headers
            val apiUrl = "https://www.instagram.com/api/v1/media/${shortcodeToId(shortcode)}/info/"
            val html = client.getText(apiUrl, mapOf(
                "User-Agent" to "Instagram 269.0.0.18.75 Android (26/8.0.0; 480dpi; 1080x1920; OnePlus; ONEPLUS A3010; OnePlus3T; qcom; en_US; 314665256)",
                "X-IG-App-ID" to "936619743392459",
                "X-IG-Capabilities" to "3brTvw==",
                "Accept-Language" to "en-US",
                "Cookie" to "",
            ))

            if (html != null) {
                val json = try { JSONObject(html) } catch (_: Exception) { null }
                val item = json?.optJSONObject("items")
                    ?: json?.optJSONArray("items")?.optJSONObject(0)

                val videoVersions = item?.optJSONArray("video_versions")
                if (videoVersions != null && videoVersions.length() > 0) {
                    val videoUrl = videoVersions.getJSONObject(0).optString("url")
                    val thumb = item.optJSONObject("image_versions2")
                        ?.optJSONArray("candidates")?.optJSONObject(0)?.optString("url")
                    val caption = item.optJSONObject("caption")?.optString("text")?.take(60)

                    return Result.Success(VideoInfo(
                        url = url,
                        title = caption?.ifEmpty { "Instagram Video" } ?: "Instagram Video",
                        thumbnail = thumb,
                        duration = item.optLong("video_duration").takeIf { it > 0 },
                        uploader = item.optJSONObject("user")?.optString("username"),
                        platform = Platform.INSTAGRAM,
                        formats = listOf(VideoFormat(
                            formatId = "best", quality = "Best Quality", ext = "mp4",
                            filesize = null, fps = item.optInt("video_codec_fps").takeIf { it > 0 },
                            vcodec = "h264", acodec = "aac",
                            hasVideo = true, hasAudio = true, downloadUrl = videoUrl,
                        )),
                    ))
                }
            }
            null
        } catch (e: Exception) {
            Log.d("VidFlow/IG-API", "App API failed: ${e.message}")
            null
        }
    }

    private fun tryInstagramEmbed(url: String, shortcode: String): Result? {
        return try {
            val cleanUrl = "https://www.instagram.com/reel/$shortcode/"
            val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"

            val html = client.getText(embedUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Referer" to "https://www.instagram.com/",
            )) ?: return null

            // Try multiple patterns for video URL
            val videoUrl = html.regexFind(""""video_url":"([^"]+)""")?.unescape()
                ?: html.regexFind("""<video[^>]+src="([^"]+)"""")
                ?: html.regexFind("""<meta property="og:video" content="([^"]+)"""")
                ?: html.regexFind("""<meta property="og:video:url" content="([^"]+)"""")
                ?: html.regexFind("""VideoObject","contentUrl":"([^"]+)""")?.unescape()
                ?: return null

            val title = html.regexFind(""""accessibility_caption":"([^"]+)""")?.take(60)
                ?: html.regexFind("""<title>([^<]+)</title>""")?.replace(" • Instagram", "")?.trim()
                ?: "Instagram Video"

            val thumb = html.regexFind(""""display_url":"([^"]+)""")?.unescape()
                ?: html.regexFind("""<meta property="og:image" content="([^"]+)"""")

            Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumb,
                duration = null, uploader = null, platform = Platform.INSTAGRAM,
                formats = listOf(VideoFormat(
                    formatId = "hd", quality = "HD", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = videoUrl,
                )),
            ))
        } catch (e: Exception) {
            Log.d("VidFlow/IG-Embed", "Embed failed: ${e.message}")
            null
        }
    }

    private fun tryYtDlpInstagram(url: String): Result? {
        return try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--extractor-retries", "3")
                addOption("--socket-timeout", "20")
                // Use Instagram app UA for better access
                addOption("--user-agent",
                    "Instagram 269.0.0.18.75 Android (26/8.0.0; 480dpi; 1080x1920; OnePlus; ONEPLUS A3010; OnePlus3T; qcom; en_US; 314665256)")
            }
            val info = YoutubeDL.getInstance().getInfo(req)
            // Use parseYtDlpInstagram instead of parseYtDlpClean to avoid format selector issues
            parseYtDlpInstagramSafe(url, info)
        } catch (e: Exception) {
            Log.d("VidFlow/IG-ytdlp", "yt-dlp failed: ${e.message}")
            null
        }
    }

    /**
     * Instagram-specific yt-dlp result parser.
     * CRITICAL: Always use needsYtDlp=true with 'best' selector for Instagram.
     * Using specific format IDs causes 'requested format is not available' error.
     */
    private fun parseYtDlpInstagramSafe(
        originalUrl: String,
        info: com.yausername.youtubedl_android.mapper.VideoInfo,
    ): Result? {
        val title = info.title ?: "Instagram Video"
        val allFormats = info.formats ?: return null

        // Find the best combined format to get metadata (thumbnail, quality)
        val bestCombined = allFormats
            .filter { (it.vcodec ?: "none") != "none" && (it.acodec ?: "none") != "none" }
            .maxByOrNull { it.height ?: 0 }
        val bestUrl = bestCombined?.url?.takeIf { it.isNotEmpty() }

        val formats = mutableListOf<VideoFormat>()

        if (bestUrl != null) {
            // Direct URL — no need for yt-dlp at download time
            formats.add(VideoFormat(
                formatId = "best", quality = "Best Quality",
                ext = "mp4", filesize = null, fps = 30,
                vcodec = "h264", acodec = "aac",
                hasVideo = true, hasAudio = true,
                downloadUrl = bestUrl, needsYtDlp = false,
            ))
        } else {
            // DASH streams — use yt-dlp with 'best' selector at download time
            formats.add(VideoFormat(
                formatId = "best", quality = "Best Quality",
                ext = "mp4", filesize = null, fps = 30,
                vcodec = "h264", acodec = "aac",
                hasVideo = true, hasAudio = true,
                downloadUrl = "", needsYtDlp = true,
                ytDlpFormatSelector = "best",
            ))
        }

        return Result.Success(VideoInfo(
            url = originalUrl, title = title,
            thumbnail = info.thumbnail,
            duration = info.duration?.toLong(),
            uploader = info.uploader,
            platform = Platform.INSTAGRAM,
            formats = formats,
        ))
    }

    // ── Threads ───────────────────────────────────────────────────────────────

    private suspend fun extractThreads(url: String): Result {
        return raceHttpAndYtDlp(url, Platform.THREADS) {
            ThreadsExtractor(client).extract(url)
                .takeIf { it is Result.Success }
                ?: tryThreadsScrape(url)
                ?: Result.Error(
                    "Threads video could not be downloaded.\n\n" +
                    "• Make sure the post is public\n" +
                    "• Threads often blocks unauthenticated access\n" +
                    "• Try copying the direct post link from the Threads app"
                )
        }
    }

    private fun tryThreadsScrape(url: String): Result? {
        return try {
            // Try the main page with different UAs
            val headers = mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                "Accept" to "text/html,application/xhtml+xml",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-Fetch-Site" to "none",
            )
            val html = client.getText(url, headers) ?: return null

            val videoUrl = html.regexFind(""""video_url":"([^"]+)""")?.unescape()
                ?: html.regexFind("""<video[^>]+src="([^"]+)"""")
                ?: html.regexFind("""<meta property="og:video" content="([^"]+)"""")
                ?: html.regexFind("""VideoObject[^}]+"contentUrl":"([^"]+)""")?.unescape()
                ?: return null

            val title = html.regexFind("""<meta property="og:description" content="([^"]+)"""")?.take(60)
                ?: "Threads Video"
            val thumb = html.regexFind("""<meta property="og:image" content="([^"]+)"""")

            Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumb,
                duration = null, uploader = null, platform = Platform.THREADS,
                formats = listOf(VideoFormat(
                    formatId = "best", quality = "Best Quality", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = videoUrl,
                )),
            ))
        } catch (e: Exception) {
            Log.d("VidFlow/Threads-Scrape", "Scrape failed: ${e.message}")
            null
        }
    }

    private fun tryYtDlpThreads(url: String): Result? {
        return try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--extractor-retries", "5")
                addOption("--socket-timeout", "30")
                // Instagram UA works for Threads too (same backend)
                addOption("--user-agent",
                    "Instagram 269.0.0.18.75 Android (26/8.0.0; 480dpi; 1080x1920; OnePlus; ONEPLUS A3010; OnePlus3T; qcom; en_US; 314665256)")
            }
            val info = YoutubeDL.getInstance().getInfo(req)

            // Same safe parsing — use 'best' selector, not specific format IDs
            val title = info.title ?: "Threads Video"
            val allFormats = info.formats ?: return null
            val bestCombined = allFormats
                .filter { (it.vcodec ?: "none") != "none" && (it.acodec ?: "none") != "none" }
                .maxByOrNull { it.height ?: 0 }
            val bestUrl = bestCombined?.url?.takeIf { it.isNotEmpty() }

            val formats = if (bestUrl != null) {
                listOf(VideoFormat(
                    formatId = "best", quality = "Best Quality", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = bestUrl, needsYtDlp = false,
                ))
            } else {
                listOf(VideoFormat(
                    formatId = "best", quality = "Best Quality", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = "",
                    needsYtDlp = true, ytDlpFormatSelector = "best",
                ))
            }

            Result.Success(VideoInfo(
                url = url, title = title, thumbnail = info.thumbnail,
                duration = info.duration?.toLong(), uploader = info.uploader,
                platform = Platform.THREADS, formats = formats,
            ))
        } catch (e: Exception) {
            Log.d("VidFlow/Threads-ytdlp", "yt-dlp failed: ${e.message}")
            null
        }
    }

    // ── Generic helpers ───────────────────────────────────────────────────────

    /**
     * Speed-optimized race: HTTP first (fast, 300-800ms), yt-dlp as fallback.
     * HTTP runs first with a 5s timeout. If it fails, yt-dlp takes over.
     * Both run on IO dispatcher — no blocking.
     */
    private suspend fun raceHttpAndYtDlp(
        url: String,
        platform: Platform,
        http: () -> Result,
    ): Result = kotlinx.coroutines.withContext(Dispatchers.IO) {
        // Fast path: lightweight HTTP (no Python startup, instant)
        val fastHttpResult = try {
            withTimeoutOrNull(5_000L) { http() }
        } catch (e: Exception) {
            null
        }

        if (fastHttpResult is Result.Success && fastHttpResult.info.formats.isNotEmpty()) {
            return@withContext fastHttpResult
        }

        // Fallback: yt-dlp (slower but handles any public video)
        if (context != null) {
            val platformSpecific = when (platform) {
                Platform.INSTAGRAM -> withTimeoutOrNull(25_000L) { tryYtDlpInstagram(url) }
                Platform.THREADS   -> withTimeoutOrNull(25_000L) { tryYtDlpThreads(url) }
                else               -> withTimeoutOrNull(20_000L) { tryYtDlp(url, platform) }
            }
            if (platformSpecific is Result.Success && platformSpecific.info.formats.isNotEmpty()) {
                return@withContext platformSpecific
            }
        }

        fastHttpResult ?: Result.Error("Could not extract video from this URL. Please verify the link is public and try again.")
    }

    internal fun tryYtDlp(url: String, platform: Platform): Result? {
        return try {
            val req = YoutubeDLRequest(url).apply {
                addOption("--dump-json")
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--socket-timeout", "15")
            }
            val info = YoutubeDL.getInstance().getInfo(req)
            parseYtDlpClean(url, info, platform)
        } catch (e: Exception) {
            Log.w("VidFlow/${platform.displayName}", "yt-dlp failed: ${e.message}")
            null
        }
    }

    /**
     * Parses yt-dlp result into clean user-friendly formats.
     * Rules:
     * - ONLY show formats that have BOTH video AND audio, OR create merged tiers
     * - NEVER show video-only formats as separate options
     * - For Facebook: never show audio-only
     * - Always sort: highest quality first, Audio Only last
     */
    private fun parseYtDlpClean(
        originalUrl: String,
        info: com.yausername.youtubedl_android.mapper.VideoInfo,
        platform: Platform,
    ): Result? {
        val allFormats = info.formats ?: return null
        val title     = info.title    ?: "Video"
        val formats   = mutableListOf<VideoFormat>()
        val seen      = mutableSetOf<String>()

        // Pass 1: Combined streams (hasVideo && hasAudio) — direct URL download
        allFormats
            .filter { f ->
                (f.vcodec ?: "none") != "none" &&
                (f.acodec ?: "none") != "none" &&
                !f.url.isNullOrEmpty()
            }
            .sortedByDescending { f -> f.height ?: 0 }
            .forEach { f ->
                val label = when {
                    (f.height ?: 0) >= 1080 -> "1080p HD"
                    (f.height ?: 0) >= 720  -> "720p HD"
                    (f.height ?: 0) >= 480  -> "480p"
                    (f.height ?: 0) >= 360  -> "360p"
                    (f.height ?: 0) >= 240  -> "240p"
                    else -> f.formatNote?.ifEmpty { null } ?: "SD"
                }
                if (seen.add(label)) {
                    formats.add(VideoFormat(
                        formatId = f.formatId ?: "", quality = label, ext = "mp4",
                        filesize = null, fps = f.fps?.toInt(),
                        vcodec = "h264", acodec = "aac",
                        hasVideo = true, hasAudio = true,
                        downloadUrl = f.url ?: "", needsYtDlp = false,
                    ))
                }
            }

        // Pass 2: For platforms with DASH (YouTube/FB/Insta) — add merge tiers if missing quality
        val hasVideoOnlyHd = allFormats.any { f ->
            (f.height ?: 0) >= 720 && (f.vcodec ?: "none") != "none"
        }
        if (hasVideoOnlyHd) {
            listOf(
                "1080p HD" to "bestvideo[height=1080]+bestaudio/bestvideo[height<=1080]+bestaudio",
                "720p HD"  to "bestvideo[height=720]+bestaudio/bestvideo[height<=720]+bestaudio",
            ).forEach { (label, selector) ->
                if (seen.add(label)) {
                    formats.add(VideoFormat(
                        formatId = selector, quality = label, ext = "mp4",
                        filesize = null, fps = null, vcodec = "h264", acodec = "aac",
                        hasVideo = true, hasAudio = true, downloadUrl = "",
                        needsYtDlp = true, ytDlpFormatSelector = selector,
                    ))
                }
            }
        }

        // Pass 3: Audio only — ONLY for non-Facebook platforms
        // IMPORTANT: Use needsYtDlp=true + bestaudio selector to ensure real MP3 output
        if (platform != Platform.FACEBOOK) {
            if (seen.add("MP3 (Audio Only)")) {
                formats.add(VideoFormat(
                    formatId = "bestaudio/best", quality = "MP3 (Audio Only)",
                    ext = "mp3", filesize = null, fps = null,
                    vcodec = null, acodec = "mp3",
                    hasVideo = false, hasAudio = true,
                    downloadUrl = "", needsYtDlp = true,
                    ytDlpFormatSelector = "bestaudio/best",
                ))
            }
        }

        if (formats.isEmpty()) return null

        return Result.Success(VideoInfo(
            url = originalUrl, title = title, thumbnail = info.thumbnail,
            duration = info.duration?.toLong(), uploader = info.uploader,
            platform = platform, formats = formats,
        ))
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Convert Instagram shortcode to numeric media ID */
    private fun shortcodeToId(code: String): Long {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_"
        var id = 0L
        for (c in code) {
            id = id * 64 + alphabet.indexOf(c)
        }
        return id
    }

    private fun normalizeUrl(url: String): String {
        var u = url
        if (!u.startsWith("http")) u = "https://$u"
        if (u.contains("youtu.be/")) {
            val id = u.substringAfterLast("youtu.be/").substringBefore("?").substringBefore("&")
            u = "https://www.youtube.com/watch?v=$id"
        }
        return u
    }
}

// ── Shared helpers ────────────────────────────────────────────────────────────

internal fun OkHttpClient.getJson(url: String, headers: Map<String, String> = emptyMap()): JSONObject? {
    val req = Request.Builder().url(url).apply {
        addHeader("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36")
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    return try {
        newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string()?.let { JSONObject(it) } else null
        }
    } catch (e: Exception) { null }
}

internal fun OkHttpClient.getText(url: String, headers: Map<String, String> = emptyMap()): String? {
    val req = Request.Builder().url(url).apply {
        addHeader("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36")
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    return try {
        newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) { null }
}

internal fun OkHttpClient.postForm(
    url: String,
    form: Map<String, String>,
    headers: Map<String, String> = emptyMap(),
): String? {
    val body = FormBody.Builder().apply { form.forEach { (k, v) -> add(k, v) } }.build()
    val req = Request.Builder().url(url).post(body).apply {
        addHeader("User-Agent", "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36")
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    return try {
        newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) { null }
}

internal fun OkHttpClient.postJson(
    url: String,
    json: String,
    headers: Map<String, String> = emptyMap(),
): String? {
    val body = json.toRequestBody("application/json; charset=utf-8".toMediaType())
    val req = Request.Builder().url(url).post(body).apply {
        addHeader("User-Agent", "VidFlow/1.1")
        addHeader("Accept", "application/json")
        headers.forEach { (k, v) -> addHeader(k, v) }
    }.build()
    return try {
        newCall(req).execute().use { resp ->
            if (resp.isSuccessful) resp.body?.string() else null
        }
    } catch (e: Exception) { null }
}

internal fun String.regexFind(pattern: String, group: Int = 1): String? =
    Pattern.compile(pattern, Pattern.DOTALL).matcher(this).let {
        if (it.find()) it.group(group) else null
    }

internal fun Long.toReadableSize(): String {
    val kb = this / 1024
    val mb = kb / 1024
    return if (mb > 0) "${mb}MB" else "${kb}KB"
}
