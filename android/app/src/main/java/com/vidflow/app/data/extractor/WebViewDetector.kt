package com.vidflow.app.data.extractor

import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient

/** One media URL spotted inside a browsed page. */
data class DetectedVideo(
    val url: String,
    /** ".mp4", ".m3u8", ".mpd", ... or null when detected by CDN host. */
    val kind: String?,
    val pageUrl: String,
)

/**
 * The browser IS the extractor (brief section 4).
 *
 * WebViewClient.shouldInterceptRequest sees every network request the page
 * makes, including media requests. When a request URL is a media file, or
 * comes from a known video CDN host, it is recorded and the download button
 * is shown. Returning null (via super) lets the page keep loading normally.
 *
 * This client shares the app-wide WebView cookie store with the Login
 * screen, so a user who logs in inside the browser is logged in for
 * extraction too, and vice versa. No cookies are read or stored here
 * directly; SessionCookies owns that.
 *
 * blob: URLs are ignored: a blob URL only exists inside the page's JS
 * context and cannot be downloaded by a second request.
 */
class WebViewDetector(
    private val onDetected: (DetectedVideo) -> Unit,
) : WebViewClient() {

    private val seen = LinkedHashSet<String>()

    // Page URL tracked on the main thread only. shouldInterceptRequest
    // runs on Chromium's network thread, so it must NEVER call a WebView
    // method such as view.url — Android throws checkThread and the app
    // crashes (seen 2026-10-27: browser tab crash, WebViewDetector.kt:42).
    @Volatile
    private var currentPageUrl: String? = null

    override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
        currentPageUrl = url
        super.onPageStarted(view, url, favicon)
    }

    override fun shouldInterceptRequest(
        view: WebView,
        request: WebResourceRequest,
    ): android.webkit.WebResourceResponse? {
        val url = request.url.toString()
        recordIfMedia(url, currentPageUrl)
        return super.shouldInterceptRequest(view, request)
    }

    override fun onPageFinished(view: WebView, url: String) {
        super.onPageFinished(view, url)
        // Some players set <video src> without a separate interceptable
        // request (e.g. cached media). Scan the DOM for video sources.
        view.evaluateJavascript(
            "(function(){var s=[];document.querySelectorAll('video').forEach(function(v){" +
                "if(v.currentSrc)s.push(v.currentSrc);if(v.src)s.push(v.src);" +
                "v.querySelectorAll('source').forEach(function(e){if(e.src)s.push(e.src)});});" +
                "return JSON.stringify(s);})();",
        ) { json ->
            if (json.isNullOrBlank()) return@evaluateJavascript
            Regex("\"(https?://[^\"]+)\"").findAll(json).forEach { match ->
                recordIfMedia(match.groupValues[1].replace("\\/", "/"), url)
            }
        }
    }

    private fun recordIfMedia(url: String, pageUrl: String?) {
        if (url.startsWith("blob:") || url.startsWith("data:")) return
        val clean = url.substringBefore("?").substringBefore("#").lowercase()
        val kind = when {
            clean.endsWith(".mp4") -> "mp4"
            clean.endsWith(".m3u8") -> "m3u8"
            clean.endsWith(".mpd") -> "mpd"
            clean.endsWith(".webm") -> "webm"
            clean.endsWith(".mov") -> "mov"
            clean.endsWith(".m4a") || clean.endsWith(".mp3") -> "audio"
            else -> null
        }
        val host = runCatching {
            android.net.Uri.parse(url).host?.lowercase() ?: ""
        }.getOrDefault("")
        val cdnHost = VIDEO_CDN_HOSTS.any { host == it || host.endsWith(".$it") }
        if (kind == null && !cdnHost) return
        // A CDN host alone is not proof of video (it also serves images),
        // so only record CDN hits that look like media paths.
        if (kind == null && !url.contains("/video", ignoreCase = true) &&
            !url.contains("mime=video", ignoreCase = true) &&
            !url.contains("videoplayback", ignoreCase = true)
        ) return
        if (!seen.add(url)) return
        onDetected(DetectedVideo(url, kind ?: "video", pageUrl ?: url))
    }

    companion object {
        private val VIDEO_CDN_HOSTS = listOf(
            "fbcdn.net", "cdninstagram.com", "tiktokcdn.com",
            "googlevideo.com", "twimg.com", "pinimg.com",
            "v.redd.it", "dmcdn.net", "dailymotion.com",
            "vod-adaptive.akamaized.net",
        )

        /** Shared rule, also usable in unit tests without a WebView. */
        fun looksLikeMedia(url: String): Boolean {
            if (url.startsWith("blob:") || url.startsWith("data:")) return false
            val clean = url.substringBefore("?").substringBefore("#").lowercase()
            if (clean.endsWith(".mp4") || clean.endsWith(".m3u8") || clean.endsWith(".mpd") ||
                clean.endsWith(".webm") || clean.endsWith(".mov") ||
                clean.endsWith(".m4a") || clean.endsWith(".mp3")
            ) return true
            val host = runCatching {
                android.net.Uri.parse(url).host?.lowercase() ?: ""
            }.getOrDefault("")
            return VIDEO_CDN_HOSTS.any { host == it || host.endsWith(".$it") } &&
                (url.contains("/video", ignoreCase = true) ||
                    url.contains("videoplayback", ignoreCase = true))
        }
    }
}
