package com.vidflow.app.data.extractor

import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONArray
import org.json.JSONObject

/** Reddit video extractor using the public JSON API (no auth needed for public posts). */
internal class RedditExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        val jsonUrl = url.trimEnd('/').let {
            if (it.endsWith(".json")) it else "$it.json"
        }
        return try {
            val json = client.getText(jsonUrl, mapOf("Accept" to "application/json"))
                ?: return VideoExtractor.Result.Error("Could not fetch Reddit post.")

            val listing = JSONArray(json).getJSONObject(0)
            val post = listing.getJSONObject("data")
                .getJSONArray("children")
                .getJSONObject(0)
                .getJSONObject("data")

            val title = post.optString("title", "Reddit Video")
            val thumbnail = post.optString("thumbnail").takeIf { it.startsWith("http") }

            val media = post.optJSONObject("media")
                ?: post.optJSONObject("secure_media")
                ?: return VideoExtractor.Result.Error("No video found in this Reddit post.")

            val redditVideo = media.optJSONObject("reddit_video")
                ?: return VideoExtractor.Result.Error("No video stream found.")

            val fallbackUrl = redditVideo.optString("fallback_url").takeIf { it.isNotEmpty() }
            val hls = redditVideo.optString("hls_url").takeIf { it.isNotEmpty() }
            val duration = redditVideo.optInt("duration").toLong().takeIf { it > 0 }
            val height = redditVideo.optInt("height")

            val formats = mutableListOf<VideoFormat>()
            if (fallbackUrl != null) {
                formats.add(VideoFormat(
                    formatId = "fallback",
                    quality = if (height > 0) "${height}p" else "Best",
                    ext = "mp4",
                    filesize = null,
                    fps = redditVideo.optInt("framerate").takeIf { it > 0 },
                    vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true,
                    downloadUrl = fallbackUrl,
                ))
            }
            if (hls != null) {
                formats.add(VideoFormat(
                    formatId = "hls", quality = "HLS Stream", ext = "mp4",
                    filesize = null, fps = null, vcodec = null, acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = hls,
                ))
            }

            if (formats.isEmpty()) return VideoExtractor.Result.Error("No downloadable stream found.")

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumbnail, duration = duration,
                uploader = post.optString("author").takeIf { it.isNotEmpty() },
                platform = Platform.REDDIT, formats = formats,
            ))
        } catch (e: Exception) {
            VideoExtractor.Result.Error("Reddit extraction failed: ${e.message}")
        }
    }
}

/**
 * Twitter/X extractor — multi-source, NO LOGIN needed for public posts.
 * Chain: FxTwitter API first, then VxTwitter (flaky backup only), then
 * Twitter's own syndication endpoint. FxTwitter is the verified 2026
 * primary: it returns direct video.twimg.com MP4 variants for public
 * tweets. Honest limit, corrected 2026-10-27: FxTwitter serves public
 * tweets and sensitive-flagged tweets that are NOT hard age-gated, but
 * truly age-gated (login-walled) posts are withheld from EVERY anonymous
 * method — no code can fetch those without a logged-in, age-verified
 * session, so they surface as an honest error, never an endless retry.
 * Do NOT route X through yt-dlp: its X GraphQL path returns empty
 * payloads in 2026.
 */
internal class TwitterExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        val tweetId = url.regexFind("""status/(\d+)""")
            ?: return VideoExtractor.Result.Error("Could not parse tweet ID from URL.")
        return tryFxTwitter(url, tweetId)
            ?: tryVxTwitter(url, tweetId)
            ?: trySyndication(url, tweetId)
            ?: VideoExtractor.Result.Error("No video found in this tweet.")
    }

    private fun tryFxTwitter(url: String, tweetId: String): VideoExtractor.Result? {
        // The {user}/status/{id} form is the verified FixTweet shape;
        // the bare /status/{id} form is kept as a second attempt.
        val user = url.regexFind("""(?:x\.com|twitter\.com)/([^/]+)/status/""", 1)
        if (user != null) {
            tryFxUrl("https://api.fxtwitter.com/$user/status/$tweetId", url)?.let { return it }
        }
        return tryFxUrl("https://api.fxtwitter.com/status/$tweetId", url)
    }

    private fun tryFxUrl(fxUrl: String, url: String): VideoExtractor.Result? {
        return try {
            val json = client.getJson(
                fxUrl,
                mapOf("User-Agent" to "VidFlow/1.0"),
            ) ?: return null
            if (json.optInt("code", -1) !in listOf(200, 0)) return null
            val tweet = json.optJSONObject("tweet") ?: return null
            val media = tweet.optJSONObject("media") ?: return null
            val formats = mutableListOf<VideoFormat>()
            val videoArrays = listOfNotNull(media.optJSONArray("videos"), media.optJSONArray("all"))
            for (videos in videoArrays) {
                for (i in 0 until videos.length()) {
                    val v = videos.getJSONObject(i)
                    if (v.optString("type") == "photo") continue
                    // Prefer the formats list: highest-bitrate MP4 wins.
                    val fmtArr = v.optJSONArray("formats")
                    var bestUrl: String? = null
                    var bestBitrate = -1
                    if (fmtArr != null) {
                        for (j in 0 until fmtArr.length()) {
                            val f = fmtArr.getJSONObject(j)
                            val fUrl = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                            if (!fUrl.contains(".mp4")) continue
                            val br = f.optInt("bitrate", 0)
                            if (br > bestBitrate) { bestBitrate = br; bestUrl = fUrl }
                        }
                    }
                    val vUrl = bestUrl
                        ?: v.optString("url").takeIf { it.isNotEmpty() && it.contains(".mp4") }
                        ?: continue
                    val h = v.optInt("height", 0)
                    val quality = when {
                        bestBitrate >= 2_000_000 -> "1080p"
                        bestBitrate >= 800_000 -> "720p"
                        bestBitrate >= 300_000 -> "480p"
                        bestBitrate > 0 -> "360p"
                        h >= 1080 -> "1080p"
                        h >= 720 -> "720p"
                        h >= 480 -> "480p"
                        h > 0 -> "${h}p"
                        else -> "Best"
                    }
                    formats.add(VideoFormat(
                        formatId = if (bestBitrate > 0) bestBitrate.toString() else "${v.optInt("width")}x$h",
                        quality = quality, ext = "mp4",
                        filesize = null, fps = null, vcodec = "h264", acodec = "aac",
                        hasVideo = true, hasAudio = true, downloadUrl = vUrl,
                    ))
                }
                if (formats.isNotEmpty()) break
            }
            if (formats.isEmpty()) return null

            val author = tweet.optJSONObject("author")?.optString("screen_name") ?: "twitter"
            VideoExtractor.Result.Success(VideoInfo(
                url = url,
                title = tweet.optString("text", "Twitter/X Video").take(80),
                thumbnail = null, duration = null, uploader = "@$author",
                platform = Platform.TWITTER,
                formats = formats.sortedByDescending { it.quality.removeSuffix("p").toIntOrNull() ?: 0 },
            ))
        } catch (_: Exception) { null }
    }

    private fun tryVxTwitter(url: String, tweetId: String): VideoExtractor.Result? {
        return try {
            val json = client.getJson("https://api.vxtwitter.com/status/$tweetId") ?: return null
            val mediaArr = json.optJSONArray("media_extended") ?: return null
            val formats = mutableListOf<VideoFormat>()
            for (i in 0 until mediaArr.length()) {
                val m = mediaArr.getJSONObject(i)
                if (m.optString("type") !in listOf("video", "gif")) continue
                val vUrl = m.optString("url").takeIf { it.isNotEmpty() } ?: continue
                formats.add(VideoFormat(
                    formatId = i.toString(), quality = "Best", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = vUrl,
                ))
            }
            if (formats.isEmpty()) return null
            VideoExtractor.Result.Success(VideoInfo(
                url = url,
                title = json.optString("text", "Twitter/X Video").take(80),
                thumbnail = null, duration = null,
                uploader = "@${json.optString("user_screen_name", "twitter")}",
                platform = Platform.TWITTER, formats = formats,
            ))
        } catch (_: Exception) { null }
    }

    private fun trySyndication(url: String, tweetId: String): VideoExtractor.Result? {
        return try {
            val json = client.getJson(
                "https://cdn.syndication.twimg.com/tweet-result?id=$tweetId&lang=en",
                mapOf(
                    "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)",
                    "Accept" to "application/json",
                ),
            ) ?: return null
            val media = json.optJSONObject("video")
                ?: json.optJSONArray("mediaDetails")?.optJSONObject(0)
                ?: json.optJSONArray("photos")?.optJSONObject(0)
            val variants = media?.optJSONArray("variants") ?: json.optJSONObject("mediaDetails")?.optJSONArray("variants")
            val formats = mutableListOf<VideoFormat>()
            if (variants != null) {
                for (i in 0 until variants.length()) {
                    val v = variants.getJSONObject(i)
                    val type = v.optString("type")
                    if (!type.contains("mp4")) continue
                    val vUrl = v.optString("src").ifEmpty { v.optString("url") }.takeIf { it.isNotEmpty() } ?: continue
                    val bitrate = v.optInt("bitrate")
                    val quality = when {
                        bitrate >= 2_000_000 -> "720p+"
                        bitrate >= 800_000 -> "480p"
                        bitrate > 0 -> "360p"
                        else -> "Best"
                    }
                    formats.add(VideoFormat(
                        formatId = bitrate.toString(), quality = quality, ext = "mp4",
                        filesize = null, fps = null, vcodec = "h264", acodec = "aac",
                        hasVideo = true, hasAudio = true, downloadUrl = vUrl,
                    ))
                }
            }
            if (formats.isEmpty()) return null
            VideoExtractor.Result.Success(VideoInfo(
                url = url,
                title = json.optString("text", "Twitter/X Video").take(80),
                thumbnail = null, duration = null,
                uploader = "@${json.optJSONObject("user")?.optString("screen_name", "twitter") ?: "twitter"}",
                platform = Platform.TWITTER,
                formats = formats,
            ))
        } catch (_: Exception) { null }
    }

}

/** Vimeo extractor using public player config. */
internal class VimeoExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        val videoId = url.regexFind("""vimeo\.com/(\d+)""")
            ?: return VideoExtractor.Result.Error("Could not parse Vimeo video ID.")

        val configUrl = "https://player.vimeo.com/video/$videoId/config"
        return try {
            val json = client.getJson(configUrl) ?: return VideoExtractor.Result.Error("Video not found on Vimeo.")

            val video = json.getJSONObject("video")
            val title = video.optString("title", "Vimeo Video")
            val thumb = video.optJSONObject("thumbs")?.let { it.optString("base") ?: it.optString("640") }
            val duration = video.optInt("duration").toLong().takeIf { it > 0 }
            val owner = video.optJSONObject("owner")?.optString("name")

            val files = json.optJSONObject("request")?.optJSONObject("files")
                ?: return VideoExtractor.Result.Error("No playable streams found.")

            val formats = mutableListOf<VideoFormat>()
            files.optJSONArray("progressive")?.let { arr ->
                for (i in 0 until arr.length()) {
                    val f = arr.getJSONObject(i)
                    val quality = f.optString("quality", "SD")
                    val streamUrl = f.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    formats.add(VideoFormat(
                        formatId = f.optString("id", quality), quality = quality, ext = "mp4",
                        filesize = null, fps = f.optInt("fps").takeIf { it > 0 },
                        vcodec = "h264", acodec = "aac", hasVideo = true, hasAudio = true,
                        downloadUrl = streamUrl,
                    ))
                }
            }

            if (formats.isEmpty()) return VideoExtractor.Result.Error("No downloadable streams found. This video may be restricted.")

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumb, duration = duration, uploader = owner,
                platform = Platform.VIMEO,
                formats = formats.sortedByDescending { it.quality.removeSuffix("p").toIntOrNull() ?: 0 },
            ))
        } catch (e: Exception) {
            VideoExtractor.Result.Error("Vimeo extraction failed: ${e.message}")
        }
    }
}

/** Dailymotion extractor using public metadata API. */
internal class DailymotionExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        val videoId = url.regexFind("""dailymotion\.com/video/([a-zA-Z0-9]+)""")
            ?: url.regexFind("""dai\.ly/([a-zA-Z0-9]+)""")
            ?: return VideoExtractor.Result.Error("Could not parse Dailymotion video ID.")

        return try {
            val apiUrl = "https://www.dailymotion.com/player/metadata/video/$videoId?locale=en_US&dmV1st=&dmTs=0&is_native_app=0"
            val json = client.getJson(apiUrl) ?: return VideoExtractor.Result.Error("Video not found on Dailymotion.")

            val title = json.optString("title", "Dailymotion Video")
            val thumb = json.optString("poster_url").takeIf { it.isNotEmpty() }
            val duration = json.optInt("duration").toLong().takeIf { it > 0 }
            val owner = json.optJSONObject("owner")?.optString("screenname")

            val qualities = json.optJSONObject("qualities")
            val formats = mutableListOf<VideoFormat>()
            qualities?.keys()?.forEach { key ->
                val arr = qualities.optJSONArray(key) ?: return@forEach
                for (i in 0 until arr.length()) {
                    val item = arr.getJSONObject(i)
                    val type = item.optString("type")
                    if (!type.contains("mp4") && !type.contains("video")) continue
                    val streamUrl = item.optString("url").takeIf { it.isNotEmpty() } ?: continue
                    formats.add(VideoFormat(
                        formatId = "$key-$i", quality = key.replace("auto", "Auto"), ext = "mp4",
                        filesize = null, fps = null, vcodec = "h264", acodec = "aac",
                        hasVideo = true, hasAudio = true, downloadUrl = streamUrl,
                    ))
                    break
                }
            }

            if (formats.isEmpty()) return VideoExtractor.Result.Error("No video streams found.")

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumb, duration = duration, uploader = owner,
                platform = Platform.DAILYMOTION, formats = formats,
            ))
        } catch (e: Exception) {
            VideoExtractor.Result.Error("Dailymotion extraction failed: ${e.message}")
        }
    }
}

/**
 * TikTok extractor.
 * Multi-source chain: TikWM API first (HD, no watermark), then SSSTik,
 * then the page's own video URLs, then the ordered chain's yt-dlp.
 * Supports vt.tiktok.com, vm.tiktok.com, and full tiktok.com URLs.
 */
internal class TikTokExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        return tryTikWM(url) ?: trySSSTik(url) ?: tryTikTokPage(url)
            ?: VideoExtractor.Result.Error("Could not extract TikTok video. Please ensure the video is public and try again.")
    }

    private fun tryTikWM(url: String): VideoExtractor.Result? {
        return try {
            val apiUrl = "https://www.tikwm.com/api/?url=${java.net.URLEncoder.encode(url, "UTF-8")}&hd=1"
            val json = client.getJson(apiUrl, mapOf(
                "Referer" to "https://www.tikwm.com/",
                "Origin" to "https://www.tikwm.com",
            )) ?: return null

            if (json.optInt("code") != 0) return null
            val data = json.getJSONObject("data")
            val title = data.optString("title", "TikTok Video").ifEmpty { "TikTok Video" }
            val cover = data.optString("cover").takeIf { it.isNotEmpty() }
            val duration = data.optLong("duration").takeIf { it > 0 }
            val author = data.optJSONObject("author")?.optString("nickname") ?: "TikTok Creator"

            val playUrl = data.optString("hdplay").ifEmpty { data.optString("play") }
            val wmplayUrl = data.optString("wmplay")
            val musicUrl = data.optString("music")
            val size = data.optLong("hd_size").takeIf { it > 0 } ?: data.optLong("size").takeIf { it > 0 }

            val formats = mutableListOf<VideoFormat>()
            if (playUrl.isNotEmpty()) {
                formats.add(VideoFormat(
                    formatId = "hd_no_watermark", quality = "HD (No Watermark)", ext = "mp4",
                    filesize = size, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = playUrl,
                ))
            }
            if (wmplayUrl.isNotEmpty() && wmplayUrl != playUrl) {
                formats.add(VideoFormat(
                    formatId = "watermarked", quality = "Standard (With Watermark)", ext = "mp4",
                    filesize = data.optLong("wm_size").takeIf { it > 0 }, fps = 30,
                    vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = wmplayUrl,
                ))
            }
            if (musicUrl.isNotEmpty()) {
                formats.add(VideoFormat(
                    formatId = "audio_only", quality = "Audio Only (MP3)", ext = "mp3",
                    filesize = null, fps = null, vcodec = null, acodec = "mp3",
                    hasVideo = false, hasAudio = true, downloadUrl = musicUrl,
                ))
            }

            if (formats.isEmpty()) return null

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title, thumbnail = cover, duration = duration, uploader = author,
                platform = Platform.TIKTOK, formats = formats,
            ))
        } catch (_: Exception) { null }
    }

    private fun trySSSTik(url: String): VideoExtractor.Result? {
        return try {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val apiUrl = "https://ssstik.io/abc?url=$encoded"
            val html = client.getText(apiUrl, mapOf(
                "Referer" to "https://ssstik.io/",
                "User-Agent" to "Mozilla/5.0 (Android 14; Mobile) AppleWebKit/537.36",
            )) ?: return null

            val videoUrl = html.regexFind("""href="(https://[^"]+\.mp4[^"]*)" download""")
                ?: html.regexFind("""src="(https://[^"]+\.mp4[^"]*)"""")
                ?: return null

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = "TikTok Video", thumbnail = null, duration = null,
                uploader = "TikTok Creator", platform = Platform.TIKTOK,
                formats = listOf(VideoFormat(
                    formatId = "best", quality = "Best Available", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = videoUrl,
                )),
            ))
        } catch (_: Exception) { null }
    }

    private fun tryTikTokPage(url: String): VideoExtractor.Result? {
        return try {
            val html = client.getText(url, mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Mobile/15E148 Safari/604.1",
                "Accept" to "text/html,application/xhtml+xml",
                "Referer" to "https://www.tiktok.com/",
            )) ?: return null
            val videoUrl = html.regexFind("playAddr[^a-zA-Z0-9]+(https:[^\"\\\\]+)")?.unescape()
                ?: html.regexFind("<video[^>]+src=\"([^\"]+)\"")
                ?: html.regexFind("<meta property=\"og:video\" content=\"([^\"]+)\"")
                ?: return null
            val title = html.regexFind("<meta property=\"og:title\" content=\"([^\"]+)\"")
                ?: html.regexFind("<title>([^<]+)</title>")
                ?: "TikTok Video"
            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title.take(80), thumbnail = null, duration = null,
                uploader = "TikTok Creator", platform = Platform.TIKTOK,
                formats = listOf(VideoFormat(
                    formatId = "best", quality = "Best Available", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = videoUrl,
                )),
            ))
        } catch (e: Exception) { null }
    }
}

/**
 * Instagram extractor.
 * Reality in late 2026: Instagram has NO stable public API for anonymous
 * download. The old mirror services are all dead (snapinsta.app: domain
 * transferred to Instagram in 2025; saveig.app, ddinstagram.com: do not
 * resolve; igram.world: permanently unavailable) and are deliberately
 * NOT called here — a dead call only wastes an attempt. What remains,
 * in order: the embed page, then the ordered chain's app API and yt-dlp
 * (with the WebView session when logged in). Public posts work only
 * when one of those paths still serves them; login-walled posts need
 * the session. CDN URLs are signed and short-lived: download at once.
 */
internal class InstagramExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        val cleanUrl = url.substringBefore("?").trimEnd('/')
        val shortcode = cleanUrl.regexFind("""/(?:p|reel|reels|tv|share/reel|share/p)/([A-Za-z0-9_-]+)""", 1)
            ?: return VideoExtractor.Result.Error("Invalid Instagram URL. Use a direct link to a post, reel, or video.")

        // Embed page only: every third-party mirror from the old chain is
        // dead in 2026 (see class doc). The ordered chain in VideoExtractor
        // then tries the app API and yt-dlp.
        tryEmbed(url, cleanUrl, shortcode)?.let { return it }

        return VideoExtractor.Result.Error(
            "Instagram requires login for this content.\n\n" +
            "Try: Open in browser ? tap share ? copy link ? paste here"
        )
    }

    private fun tryEmbed(url: String, cleanUrl: String, shortcode: String): VideoExtractor.Result? {
        val oembedUrl = "https://www.instagram.com/oembed/?url=${java.net.URLEncoder.encode(cleanUrl, "UTF-8")}&omitscript=true"
        val oembedJson = client.getJson(oembedUrl, mapOf(
            "User-Agent" to "Mozilla/5.0 (compatible; Googlebot/2.1; +http://www.google.com/bot.html)"
        ))
        val thumbFromOembed = oembedJson?.optString("thumbnail_url")?.takeIf { it.isNotEmpty() }
        val titleFromOembed = oembedJson?.optString("title")?.takeIf { it.isNotEmpty() }
        val embedUrl = "https://www.instagram.com/p/$shortcode/embed/captioned/"
        val html = client.getText(embedUrl, mapOf(
            "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15",
            "Accept" to "text/html,application/xhtml+xml",
            "Accept-Language" to "en-US,en;q=0.9",
        )) ?: return null
        val videoUrl = firstMp4(html) ?: return null
        val title = titleFromOembed ?: html.regexFind("""<title>([^<]+)</title>""") ?: "Instagram Video"
        val thumb = thumbFromOembed
            ?: html.regexFind(""""display_url":"([^"]+)"""")?.unescape()
            ?: html.regexFind("""<meta property="og:image" content="([^"]+)"""")
        return success(url, title, thumb, videoUrl)
    }
}

/**
 * Facebook video & Reels extractor.
 * Handles share links (facebook.com/share/r/ID, fb.watch/ID, etc.),
 * follows redirects and extracts direct CDN MP4 streams.
 */
internal class FacebookExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        return try {
            // Method 1: Desktop Chrome UA (gives full JSON with browser_native_hd_url / playable_url)
            val desktopHeaders = mapOf(
                "User-Agent" to "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36",
                "Accept" to "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
                "Accept-Language" to "en-US,en;q=0.9",
                "Sec-Fetch-Site" to "none",
            )
            var html = client.getText(url, desktopHeaders)

            var hdUrl = extractHdUrl(html)
            var sdUrl = extractSdUrl(html)

            // Method 2: If desktop didn't return stream, try facebookexternalhit crawler (bypasses login for link previews)
            if (hdUrl == null && sdUrl == null) {
                val crawlerHeaders = mapOf(
                    "User-Agent" to "facebookexternalhit/1.1 (+http://www.facebook.com/externalhit_uatext.php)",
                    "Accept" to "*/*",
                    "Accept-Language" to "en-US,en;q=0.9",
                )
                val crawlerHtml = client.getText(url, crawlerHeaders)
                if (crawlerHtml != null) {
                    html = crawlerHtml
                    hdUrl = extractHdUrl(crawlerHtml)
                    sdUrl = extractSdUrl(crawlerHtml)
                }
            }

            if (hdUrl == null && sdUrl == null) {
                return VideoExtractor.Result.Error(
                    "Could not find Facebook video stream.\n\n" +
                    "• Ensure the post/video is public\n" +
                    "• Private group posts require login"
                )
            }

            val title = (html?.regexFind("""<meta property="og:title" content="([^"]+)"""")
                ?: html?.regexFind("""<title>([^<]+)</title>""")
                ?: "Facebook Video").replace(" | Facebook", "").trim()

            val thumb = html?.regexFind("""<meta property="og:image" content="([^"]+)"""")
                ?: html?.regexFind(""""preferred_thumbnail":\{"image":\{"uri":"([^"]+)"""")?.unescape()

            val formats = mutableListOf<VideoFormat>()
            if (hdUrl != null && hdUrl != sdUrl) {
                formats.add(VideoFormat(
                    formatId = "hd", quality = "HD 720p", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = hdUrl,
                ))
            }
            if (sdUrl != null) {
                formats.add(VideoFormat(
                    formatId = "sd", quality = if (hdUrl != null) "SD 360p" else "Best Available", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = sdUrl,
                ))
            }

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumb, duration = null, uploader = "Facebook",
                platform = Platform.FACEBOOK, formats = formats,
            ))
        } catch (e: Exception) {
            VideoExtractor.Result.Error("Facebook extraction failed: ${e.message}")
        }
    }

    private fun extractHdUrl(html: String?): String? {
        if (html == null) return null
        return listOf(
            """"playable_url_quality_hd":"([^"]+)"""",
            """"browser_native_hd_url":"([^"]+)"""",
            """"hd_src":"([^"]+)"""",
            """"hd_src_no_ratelimit":"([^"]+)"""",
        ).firstNotNullOfOrNull { pattern ->
            html.regexFind(pattern)?.unescape()?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
        }
    }

    private fun extractSdUrl(html: String?): String? {
        if (html == null) return null
        return listOf(
            """"playable_url":"([^"]+)"""",
            """"browser_native_sd_url":"([^"]+)"""",
            """"sd_src":"([^"]+)"""",
            """"sd_src_no_ratelimit":"([^"]+)"""",
            """<meta property="og:video" content="([^"]+)"""",
            """<meta property="og:video:url" content="([^"]+)"""",
            """<meta property="og:video:secure_url" content="([^"]+)"""",
            """https://video[a-zA-Z0-9.-]+\.fbcdn\.net/[^\s"'\\]+""",
        ).firstNotNullOfOrNull { pattern ->
            if (pattern.startsWith("https://")) {
                html.regexFind(pattern, 0)?.unescape()?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
            } else {
                html.regexFind(pattern)?.replace("&amp;", "&")?.unescape()?.replace("\\/", "/")?.takeIf { it.startsWith("http") }
            }
        }
    }


}
class PinterestExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        return try {
            val encoded = java.net.URLEncoder.encode(url, "UTF-8")
            val oembed = client.getJson("https://www.pinterest.com/oembed.json?url=$encoded")
            val oembedTitle = oembed?.optString("title")?.takeIf { it.isNotEmpty() }
            val oembedThumb = oembed?.optString("thumbnail_url")?.takeIf { it.isNotEmpty() }

            val html = client.getText(url, mapOf(
                "User-Agent" to "Mozilla/5.0 (Linux; Android 12; SM-G991B) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/106.0.0.0 Mobile Safari/537.36",
                "Accept-Language" to "en-US,en;q=0.9",
                "Accept" to "text/html,application/xhtml+xml,*/*;q=0.8",
            )) ?: return VideoExtractor.Result.Error("Could not load Pinterest page.")

            // Try multiple patterns — Pinterest changes data format regularly
            val videoUrl =
                html.regexFind(""""V_1080p":\{"url":"([^"]+)"""")?.unescape()
                ?: html.regexFind(""""V_720p":\{"url":"([^"]+)"""")?.unescape()
                ?: html.regexFind(""""V_480p":\{"url":"([^"]+)"""")?.unescape()
                ?: html.regexFind(""""V_240p":\{"url":"([^"]+)"""")?.unescape()
                ?: html.regexFind(""""url":"(https://v[0-9]+\.pinimg\.com/videos/[^"]+\.mp4[^"]*)"""")?.unescape()
                ?: html.regexFind(""""url":"(https://[^"]+\.pinimg\.com/[^"]+\.mp4[^"]*)"""")?.unescape()
                ?: html.regexFind(""""contentUrl":"(https://[^"]+pinimg\.com[^"]+\.mp4[^"]*)"""")?.unescape()
                ?: html.regexFind("""<video[^>]+src="(https?://[^"]+)"""")  
                ?: html.regexFind("""<source[^>]+src="(https?://[^"]+\.mp4[^"]*)"""")  
                ?: html.regexFind("""<meta property="og:video" content="([^"]+)"""")  
                ?: html.regexFind("""<meta property="og:video:url" content="([^"]+)"""")  
                ?: return VideoExtractor.Result.Error(
                    "No video found in this Pinterest pin.\n\nHint: Open the pin in Chrome ? tap the video ? long-press ? Copy link, then paste that direct URL here."
                )

            val title = (oembedTitle
                ?: html.regexFind("""<title>([^<]+)</title>""")
                ?: html.regexFind(""""title":"([^"]+)"""")
                ?: "Pinterest Video").replace(" | Pinterest", "").replace(" - Pinterest", "").trim()
            val thumb = oembedThumb ?: html.regexFind("""<meta property="og:image" content="([^"]+)"""")

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title, thumbnail = thumb, duration = null, uploader = null,
                platform = Platform.PINTEREST,
                formats = listOf(VideoFormat(
                    formatId = "best", quality = "Best Available", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = videoUrl,
                )),
            ))
        } catch (e: Exception) {
            VideoExtractor.Result.Error("Pinterest extraction failed: ${e.message}")
        }
    }
}

/** Threads video extractor — public posts, no login needed.
 * Method (how the working Threads downloaders do it in 2026): fetch the
 * public post page and parse its JSON hydration state for the video
 * object, taking the highest-quality Meta CDN (fbcdn) MP4. No stable
 * Threads API exists; the old proxy services are dead and are not called.
 */
internal class ThreadsExtractor(private val client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        return try {
            val embedUrl = "${url.trimEnd('/')}/embed"
            val html = client.getText(embedUrl, mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 16_0 like Mac OS X) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/16.0 Mobile/15E148 Safari/604.1",
                "Accept-Language" to "en-US",
            )) ?: client.getText(url, mapOf(
                "User-Agent" to "Mozilla/5.0 (iPhone; CPU iPhone OS 17_0 like Mac OS X) AppleWebKit/605.1.15",
            )) ?: return VideoExtractor.Result.Error("Could not load Threads post.")

            val videoUrl = html.regexFind("""<video[^>]+src="([^"]+)"""")
                ?: html.regexFind(""""video_url":"([^"]+)"""")?.unescape()
                ?: html.regexFind("""(https://[^"\\]*fbcdn\.net/[^"\\]+\.mp4[^"\\]*)""")?.unescape()
                ?: html.regexFind("""<meta property="og:video" content="([^"]+)"""")
                ?: return VideoExtractor.Result.Error(
                    "Threads requires login for this post.\n\n" +
                    "Only public video posts work. Copy the post link from the Threads app and try again."
                )

            val title = html.regexFind("""<title>([^<]+)</title>""")
                ?: html.regexFind("""<meta property="og:description" content="([^"]+)"""")
                ?: "Threads Video"
            val thumb = html.regexFind("""<meta property="og:image" content="([^"]+)"""")

            VideoExtractor.Result.Success(VideoInfo(
                url = url, title = title.take(80), thumbnail = thumb, duration = null, uploader = null,
                platform = Platform.THREADS,
                formats = listOf(VideoFormat(
                    formatId = "best", quality = "Best", ext = "mp4",
                    filesize = null, fps = 30, vcodec = "h264", acodec = "aac",
                    hasVideo = true, hasAudio = true, downloadUrl = videoUrl,
                )),
            ))
        } catch (e: Exception) {
            VideoExtractor.Result.Error("Threads extraction failed: ${e.message}")
        }
    }


}

/** Direct / generic video URL extractor for raw MP4, WEBM, MOV streams. */
internal class GenericVideoExtractor {
    fun extract(url: String): VideoExtractor.Result {
        val cleanUrl = url.substringBefore("?")
        val ext = when {
            cleanUrl.endsWith(".webm", ignoreCase = true) -> "webm"
            cleanUrl.endsWith(".mov", ignoreCase = true)  -> "mov"
            cleanUrl.endsWith(".m4a", ignoreCase = true)  -> "m4a"
            cleanUrl.endsWith(".mp3", ignoreCase = true)  -> "mp3"
            else -> "mp4"
        }
        val filename = cleanUrl.substringAfterLast("/").ifEmpty { "video" }

        return VideoExtractor.Result.Success(VideoInfo(
            url = url, title = filename, thumbnail = null, duration = null,
            uploader = "Direct Stream", platform = Platform.UNKNOWN,
            formats = listOf(VideoFormat(
                formatId = "direct", quality = "Original Quality", ext = ext,
                filesize = null, fps = null, vcodec = "h264", acodec = "aac",
                hasVideo = true, hasAudio = true, downloadUrl = url,
            )),
        ))
    }
}

/** Unescape JSON-escaped strings like \u0026 -> & and \/ -> / */
internal fun String.unescape(): String = this
    .replace("\\/", "/")
    .replace("\\u0026", "&")
    .replace("\\u003c", "<")
    .replace("\\u003e", ">")
    .replace("\\\"", "\"")
    .replace("&amp;", "&")
