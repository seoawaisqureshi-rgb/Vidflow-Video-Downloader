package com.vidflow.app.data.model

import kotlinx.serialization.Serializable

@Serializable
enum class DownloadStatus {
    QUEUED,
    FETCHING_INFO,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    CANCELLED
}

@Serializable
data class VideoFormat(
    val formatId: String,
    val quality: String,          // e.g. "1080p", "720p", "audio only"
    val ext: String,              // "mp4", "webm", "m4a"
    val filesize: Long?,          // bytes, may be null
    val fps: Int?,
    val vcodec: String?,
    val acodec: String?,
    val hasVideo: Boolean,
    val hasAudio: Boolean,
    val downloadUrl: String = "",
    // If true → use yt-dlp execute() for this format (DASH merge, FB audio+video, etc.)
    val needsYtDlp: Boolean = false,
    // yt-dlp -f selector string (used when needsYtDlp=true OR downloadUrl is empty for YouTube)
    val ytDlpFormatSelector: String = "",
)

@Serializable
data class VideoInfo(
    val url: String,
    val title: String,
    val thumbnail: String?,
    val duration: Long?,          // seconds
    val uploader: String?,
    val platform: Platform,
    val formats: List<VideoFormat>,
    val description: String? = null,
    val viewCount: Long? = null,
    val uploadDate: String? = null,
)

@Serializable
enum class Platform(val displayName: String, val domains: List<String>) {
    YOUTUBE("YouTube", listOf("youtube.com", "youtu.be", "yt.be", "youtube-nocookie.com")),
    INSTAGRAM("Instagram", listOf("instagram.com", "instagr.am", "cdninstagram.com")),
    TIKTOK("TikTok", listOf("tiktok.com", "vm.tiktok.com", "vt.tiktok.com", "m.tiktok.com")),
    FACEBOOK("Facebook", listOf("facebook.com", "fb.com", "fb.watch", "m.facebook.com", "web.facebook.com")),
    TWITTER("X / Twitter", listOf("twitter.com", "x.com", "t.co", "twimg.com")),
    REDDIT("Reddit", listOf("reddit.com", "redd.it", "v.redd.it", "old.reddit.com")),
    VIMEO("Vimeo", listOf("vimeo.com", "player.vimeo.com")),
    DAILYMOTION("Dailymotion", listOf("dailymotion.com", "dai.ly")),
    PINTEREST("Pinterest", listOf("pinterest.com", "pin.it", "pinterest.co.uk", "pinterest.fr", "pinterest.ca", "pinterest.de")),
    THREADS("Threads", listOf("threads.net", "www.threads.net", "threads.com", "www.threads.com")),
    WHATSAPP("WhatsApp", listOf("whatsapp.com")),
    UNKNOWN("Unknown", emptyList());

    companion object {
        fun fromUrl(url: String): Platform {
            val lower = url.lowercase().trim()
            val cleanedUrl = if (!lower.startsWith("http")) "https://$lower" else lower

            val host = Regex("""^https?://([^/?#]+)""").find(cleanedUrl)
                ?.groupValues?.get(1)
                ?.substringBefore(":")
                ?: ""

            return entries.firstOrNull { p ->
                p.domains.any { domain ->
                    when {
                        host.isNotEmpty() -> host == domain || host.endsWith(".$domain")
                        else -> lower.contains("://$domain/") || lower.contains("://$domain?") || lower.endsWith("://$domain")
                    }
                }
            } ?: UNKNOWN
        }
    }
}
