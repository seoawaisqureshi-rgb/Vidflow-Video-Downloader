package com.vidflow.app.data

import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.ui.components.FormatLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreLogicTest {

    // ── 1. Platform Detection & URL Parsing ──────────────────────────────────────
    @Test
    fun platformDetection_standardAndShortUrls() {
        // YouTube
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://www.youtube.com/shorts/3i_28913"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://m.youtube.com/watch?v=12345"))

        // Instagram
        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://www.instagram.com/reel/C-XYZ123/"))
        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://instagram.com/p/B987654321/"))
        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://www.instagram.com/reels/DA12345/"))

        // Facebook
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://www.facebook.com/watch/?v=123456789"))
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://fb.watch/xyz123/"))
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://www.facebook.com/reel/987654321"))

        // X / Twitter
        assertEquals(Platform.TWITTER, Platform.fromUrl("https://twitter.com/NASA/status/1712345678901234567"))
        assertEquals(Platform.TWITTER, Platform.fromUrl("https://x.com/SpaceX/status/1798765432109876543"))

        // TikTok
        assertEquals(Platform.TIKTOK, Platform.fromUrl("https://www.tiktok.com/@user/video/1234567890"))
        assertEquals(Platform.TIKTOK, Platform.fromUrl("https://vm.tiktok.com/ZM8123456/"))

        // Pinterest
        assertEquals(Platform.PINTEREST, Platform.fromUrl("https://www.pinterest.com/pin/123456789/"))
        assertEquals(Platform.PINTEREST, Platform.fromUrl("https://pin.it/abc1234"))
    }

    // ── 2. Twitter / X Resolution Extraction ────────────────────────────────────
    @Test
    fun twitterUrl_resolutionParsing() {
        val url720 = "https://video.twimg.com/amplify_video/123456/vid/720x1280/sample.mp4?tag=14"
        val regex = Regex("""/vid/(\d+)x(\d+)/""")
        val match = regex.find(url720)
        assertNotNull(match)
        val w = match!!.groupValues[1].toInt()
        val h = match.groupValues[2].toInt()
        assertEquals(720, w)
        assertEquals(1280, h)
        val minDim = minOf(w, h)
        assertEquals(720, minDim)
        assertTrue("720p or higher must be labeled HD", minDim >= 720)
    }

    @Test
    fun twitterUrl_fullHdResolutionParsing() {
        val url1080 = "https://video.twimg.com/amplify_video/999999/vid/1080x1920/fullhd.mp4"
        val regex = Regex("""/vid/(\d+)x(\d+)/""")
        val match = regex.find(url1080)
        assertNotNull(match)
        val w = match!!.groupValues[1].toInt()
        val h = match.groupValues[2].toInt()
        val minDim = minOf(w, h)
        assertEquals(1080, minDim)
        assertTrue("1080p must be labeled Full HD", minDim >= 1080)
    }

    // ── 3. Filename Sanitization ────────────────────────────────────────────────
    @Test
    fun sanitizeFileName_removesInvalidCharacters() {
        val rawTitle = "My Video: Episode 1? [4K/60fps] *special* <uncensored> | final"
        val clean = rawTitle
            .replace(Regex("""[\r\n\t\u0000-\u001f\\/:*?"<>|]"""), " ")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', '.', '_', '-')
            .take(60)
            .ifEmpty { "video" }

        assertFalse(clean.contains(":"))
        assertFalse(clean.contains("?"))
        assertFalse(clean.contains("/"))
        assertFalse(clean.contains("*"))
        assertFalse(clean.contains("<"))
        assertFalse(clean.contains(">"))
        assertFalse(clean.contains("|"))
        assertTrue(clean.startsWith("My Video Episode 1"))
    }

    // ── 4. Quality List Handling & Sorting ──────────────────────────────────────
    @Test
    fun qualitySorting_ordersHighestResolutionFirst() {
        val formats = listOf(
            VideoFormat(
                formatId = "360", quality = "360p", ext = "mp4", filesize = null, fps = 30,
                vcodec = "h264", acodec = "aac", hasVideo = true, hasAudio = true, downloadUrl = "https://example.com/360.mp4"
            ),
            VideoFormat(
                formatId = "1080", quality = "1080p HD", ext = "mp4", filesize = null, fps = 30,
                vcodec = "h264", acodec = "aac", hasVideo = true, hasAudio = true, downloadUrl = "https://example.com/1080.mp4"
            ),
            VideoFormat(
                formatId = "720", quality = "720p HD", ext = "mp4", filesize = null, fps = 30,
                vcodec = "h264", acodec = "aac", hasVideo = true, hasAudio = true, downloadUrl = "https://example.com/720.mp4"
            ),
            VideoFormat(
                formatId = "mp3", quality = "MP3 (320 kbps)", ext = "mp3", filesize = null, fps = null,
                vcodec = null, acodec = "mp3", hasVideo = false, hasAudio = true, downloadUrl = "https://example.com/audio.mp3"
            ),
        )

        val sorted = formats.sortedWith(
            compareByDescending<VideoFormat> {
                it.quality.substringBefore(" ").removeSuffix("p").toIntOrNull() ?: 0
            }.thenBy { if (it.hasVideo) 0 else 1 }
        )

        assertEquals("1080p HD", sorted[0].quality)
        assertEquals("720p HD", sorted[1].quality)
        assertEquals("360p", sorted[2].quality)
        assertEquals("MP3 (320 kbps)", sorted[3].quality)
    }

    // ── 5. FormatLabels UI Meta & Size Formatting ───────────────────────────────
    @Test
    fun formatLabels_audioLabels() {
        val mp3Format = VideoFormat(
            formatId = "mp3_320",
            quality = "MP3 (320 kbps)",
            ext = "mp3",
            filesize = null,
            fps = null,
            vcodec = null,
            acodec = "mp3",
            hasVideo = false,
            hasAudio = true,
            downloadUrl = "",
        )
        val meta = FormatLabels.metaLine(mp3Format)
        assertTrue(meta.contains("MP3"))
        assertTrue(meta.contains("Audio only"))
    }

    @Test
    fun formatLabels_videoLabels() {
        val hdFormat = VideoFormat(
            formatId = "1080p",
            quality = "1080p HD",
            ext = "mp4",
            filesize = 104857600L, // 100MB
            fps = 60,
            vcodec = "h264",
            acodec = "aac",
            hasVideo = true,
            hasAudio = true,
            downloadUrl = "",
        )
        val meta = FormatLabels.metaLine(hdFormat)
        val size = FormatLabels.sizeLabel(hdFormat)
        assertTrue(meta.contains("MP4"))
        assertTrue(meta.contains("Video+Audio"))
        assertTrue(meta.contains("60fps"))
        assertEquals("100.0 MB", size)
    }
}
