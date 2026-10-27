package com.vidflow.app.data.extractor

import com.vidflow.app.data.model.Platform
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class VideoExtractorTest {

    private lateinit var extractor: VideoExtractor

    @Before
    fun setup() {
        extractor = VideoExtractor(OkHttpClient())
    }

    @Test
    fun fromUrl_youtubeVariants() {
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://www.youtube.com/watch?v=dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://youtu.be/dQw4w9WgXcQ"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://www.youtube.com/shorts/3i_28913"))
        assertEquals(Platform.YOUTUBE, Platform.fromUrl("https://m.youtube.com/watch?v=abc12345"))
    }

    @Test
    fun fromUrl_instagramVariants() {
        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://www.instagram.com/reel/C123456789/"))
        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://instagram.com/p/B987654321/"))
        assertEquals(Platform.INSTAGRAM, Platform.fromUrl("https://www.instagram.com/tv/A12345/"))
    }

    @Test
    fun fromUrl_tiktokVariants() {
        assertEquals(Platform.TIKTOK, Platform.fromUrl("https://www.tiktok.com/@creator/video/7123456789012345678"))
        assertEquals(Platform.TIKTOK, Platform.fromUrl("https://vm.tiktok.com/ZM8123456/"))
    }

    @Test
    fun fromUrl_twitterVariants() {
        assertEquals(Platform.TWITTER, Platform.fromUrl("https://twitter.com/NASA/status/1712345678901234567"))
        assertEquals(Platform.TWITTER, Platform.fromUrl("https://x.com/SpaceX/status/1798765432109876543"))
    }

    @Test
    fun fromUrl_facebookVariants() {
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://www.facebook.com/watch/?v=123456789"))
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://fb.watch/m123456/"))
        assertEquals(Platform.FACEBOOK, Platform.fromUrl("https://www.facebook.com/reel/987654321098765"))
    }

    @Test
    fun fromUrl_redditVariants() {
        assertEquals(Platform.REDDIT, Platform.fromUrl("https://www.reddit.com/r/aww/comments/abc1234/cute_puppy/"))
        assertEquals(Platform.REDDIT, Platform.fromUrl("https://v.redd.it/xyz1234567"))
    }

    @Test
    fun fromUrl_vimeoAndDailymotion() {
        assertEquals(Platform.VIMEO, Platform.fromUrl("https://vimeo.com/76979871"))
        assertEquals(Platform.DAILYMOTION, Platform.fromUrl("https://www.dailymotion.com/video/x8abcdef"))
        assertEquals(Platform.DAILYMOTION, Platform.fromUrl("https://dai.ly/x8abcdef"))
    }

    @Test
    fun fromUrl_threadsAndPinterest() {
        assertEquals(Platform.THREADS, Platform.fromUrl("https://www.threads.net/@user/post/C123456"))
        assertEquals(Platform.PINTEREST, Platform.fromUrl("https://www.pinterest.com/pin/1234567890123456/"))
        assertEquals(Platform.PINTEREST, Platform.fromUrl("https://pin.it/7abcdef"))
    }

    @Test
    fun fromUrl_unknownPlatform() {
        assertEquals(Platform.UNKNOWN, Platform.fromUrl("https://unsupported-site.com/video.mp4"))
        assertEquals(Platform.UNKNOWN, Platform.fromUrl("https://example.org/page"))
    }

    @Test
    fun facebookStories_requiresLogin() = runBlocking {
        // Stories without a WebView session ask for login instead of a hard error.
        val result = extractor.extract("https://www.facebook.com/stories/12345")
        assertTrue(result is VideoExtractor.Result.LoginRequired)
        val login = result as VideoExtractor.Result.LoginRequired
        assertEquals(Platform.FACEBOOK, login.platform)
        assertTrue(login.message.contains("Login required"))
    }

    @Test
    fun instagramStories_requiresLogin() = runBlocking {
        // Stories without a WebView session ask for login instead of a hard error.
        val result = extractor.extract("https://www.instagram.com/stories/user/12345/")
        assertTrue(result is VideoExtractor.Result.LoginRequired)
        val login = result as VideoExtractor.Result.LoginRequired
        assertEquals(Platform.INSTAGRAM, login.platform)
        assertTrue(login.message.contains("Login required"))
    }

    @Test
    fun platformProperties_allConfigured() {
        for (platform in Platform.entries) {
            assertTrue("Platform displayName must not be empty", platform.displayName.isNotEmpty())
            if (platform != Platform.UNKNOWN) {
                assertTrue("Known platform must have at least one domain", platform.domains.isNotEmpty())
            }
        }
    }
}
