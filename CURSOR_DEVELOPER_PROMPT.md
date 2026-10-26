# VidFlow Android — Fix Remaining Issues (Cursor Prompt)

## Project Location
c:\Users\HP\Vibe Coding\Video Downloader\android

## Tech Stack
- Kotlin + Jetpack Compose
- Gradle (Kotlin DSL)
- youtubedl-android (yt-dlp wrapper) + FFmpeg
- OkHttp3 + Room DB
- MVVM architecture

## TASK 1 — Fix App Icon (CRITICAL)

The app icon is not displaying correctly on device. The current approach used PNG files + adaptive icon XML but it still shows the old default icon.

**What to do:**
1. Open `android/app/src/main/res/drawable/ic_launcher_foreground.xml`
2. Replace it with a premium vector icon: large download arrow (bold, fills 90% of 108x108 viewport), purple (#8B5CF6) stem, cyan (#06B6D4) chevron arrow head, purple bottom tray bar. All elements MUST stay within the 72dp safe zone (centered in 108dp canvas).
3. The `android/app/src/main/res/drawable/ic_launcher_background.xml` should be a dark navy gradient (#0F172A to #1E1B4B).
4. The `android/app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` adaptive icon XML MUST use:
   ```xml
   <background android:drawable="@drawable/ic_launcher_background"/>
   <foreground android:drawable="@drawable/ic_launcher_foreground"/>
   ```
5. Also copy the foreground PNG to each mipmap folder as `ic_launcher.png` and `ic_launcher_round.png` at correct sizes: mdpi=48, hdpi=72, xhdpi=96, xxhdpi=144, xxxhdpi=192.
6. After icon fix, run: `.\gradlew assembleDebug` and confirm BUILD SUCCESSFUL.

---

## TASK 2 — Fix Multiple Simultaneous Downloads UI

**Problem:** When video A is downloading, user can analyze video B but the Download button does NOT appear — only "Download Another" button shows.

**Root cause:** `HomeViewModel.kt` collect block sets `_uiState.downloadStatus = DOWNLOADING` for the current download, and `HomeScreen.kt` DownloadSection hides the Download button when status != null.

**What to do in `HomeViewModel.kt`:**
- Remove `downloadStatus` from `HomeUiState` entirely OR
- Make `startDownload()` NOT touch `downloadStatus` after download starts
- The "active downloads" should be tracked separately via `activeDownloads: StateFlow<Map<String, DownloadEntry>>` exposed from `DownloadService.downloads`

**What to do in `HomeScreen.kt`:**
- The Download button should show when: `state.videoInfo != null && state.selectedFormat != null` — REGARDLESS of what is currently downloading
- Show active downloads as cards ABOVE or BELOW the URL input section, driven by `DownloadService.downloads` directly
- Each active download card shows: title, progress bar, downloaded/total size, speed, pause button, cancel button

---

## TASK 3 — Fix Progress Bar (Show Downloaded/Total Size)

**Problem:** Progress bar shows only "45%" — NOT "45.2MB / 120MB • 2.1 MB/s"

**Root cause:** The `ServiceState.Downloading` has `bytesDownloaded` and `totalBytes` but the DownloadSection composable was hidden (see Task 2). Also `NotificationHelper` may not be formatting size correctly.

**What to do:**
1. In `HomeScreen.kt`, find `DownloadSection` composable and its DOWNLOADING branch
2. Add size display: `"${fmtBytes(downloadedBytes)} / ${fmtBytes(totalBytes)}"` below the progress bar
3. Add speed display: `"${speedKBps} KB/s"` on the right
4. In `NotificationHelper.kt`, ensure notification shows: `"$percent% • ${fmtMB(bytesDownloaded)} / ${fmtMB(totalBytes)}"`
5. Verify `DownloadEngine.kt` actually calls `onProgress()` with non-zero `bytesDownloaded` and correct `totalBytes` from HTTP `Content-Length` header

---

## TASK 4 — Fix Link Analysis Speed

**Problem:** Every URL takes 10-30 seconds to analyze.

**Root cause:** Extraction is sequential — tries HTTP first (3-5s), then falls back to yt-dlp startup (5-8s).

**What to do in `VideoExtractor.kt`:**
```kotlin
// Run HTTP extractor and yt-dlp IN PARALLEL, return whichever succeeds first
suspend fun extract(url: String): Result = coroutineScope {
    val httpDeferred = async { tryHttpExtraction(url) }
    val ytdlpDeferred = async { tryYtDlp(url, platform) }
    
    // Wait for first non-error result
    select<Result> {
        httpDeferred.onAwait { if (it is Result.Success) it else null }
        ytdlpDeferred.onAwait { it }
    } ?: httpDeferred.await() ?: ytdlpDeferred.await()
        ?: Result.Error("Could not extract video from this URL")
}
```

Also add a 15-second timeout to yt-dlp calls:
```kotlin
withTimeout(15_000L) { YoutubeDL.getInstance().getInfo(req) }
```

---

## TASK 5 — Fix Instagram Downloads

**Problem:** Returns "login required" error.

**What to do:**
Use the SaveIG/SnapInstagram public API as a third-party extractor:

```kotlin
// In PlatformExtractors.kt, InstagramExtractor class
fun extract(url: String): VideoExtractor.Result {
    // Method 1: Try snapinsta public API
    val encoded = URLEncoder.encode(url, "UTF-8")
    val apiUrl = "https://snapinsta.app/api/ajaxSearch"
    val response = client.post(apiUrl, 
        body = "q=$encoded&t=media&lang=en",
        headers = mapOf(
            "Content-Type" to "application/x-www-form-urlencoded",
            "X-Requested-With" to "XMLHttpRequest",
            "Referer" to "https://snapinsta.app/"
        )
    )
    // Parse response HTML for video URL
    val videoUrl = response?.regexFind("""<a[^>]+href="(https://[^"]+\.mp4[^"]*)"[^>]*>""")
    if (videoUrl != null) return Result.Success(...)
    
    // Method 2: Instagram embed with full cookie simulation
    // ...existing embed approach...
    
    // Method 3: Clear error message
    return Result.Error("Instagram requires login for this content.\n\nTry: Open in browser → tap share → copy link → paste here")
}
```

---

## TASK 6 — Fix Threads Downloads

Same approach as Instagram Task 5. Use a Threads-specific public downloader API or show a clear message.

---

## TASK 7 — Pinterest Fix

**What to do in `PlatformExtractors.kt`:**
```kotlin
class PinterestExtractor(client: OkHttpClient) {
    fun extract(url: String): VideoExtractor.Result {
        // Use Pinterest oEmbed API
        val oembed = client.getJson("https://www.pinterest.com/oembed.json?url=${URLEncoder.encode(url)}")
        
        // Then fetch the pin page to get video URL
        val html = client.getText(url, mobileHeaders)
        val videoUrl = html.regexFind(""""url":"(https://v1\.pinimg\.com/[^"]+\.mp4)""")
            ?: html.regexFind(""""contentUrl":"([^"]+\.mp4[^"]*)""")
        ...
    }
}
```

---

## TASK 8 — Facebook Stories Clear Error

In `VideoExtractor.kt` `extractFacebook()` function:
```kotlin
if (url.contains("/stories/") || url.contains("story_fbid")) {
    return Result.Error(
        "Facebook Stories cannot be downloaded — they expire after 24 hours and require login.\n\n" +
        "Only public Facebook post videos and Reels are supported."
    )
}
```

---

## HOW TO BUILD

```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd "c:\Users\HP\Vibe Coding\Video Downloader\android"
.\gradlew assembleDebug
```

APK output: `android\app\build\outputs\apk\debug\app-debug.apk`

## IMPORTANT NOTES

- The project uses `youtubedl-android` library which wraps yt-dlp. On first app launch it needs internet to download yt-dlp binary (auto-updates daily).
- FFmpeg is bundled for merging DASH video+audio streams (YouTube HD, Facebook).
- Instagram/Threads will NEVER work reliably without either: (a) user-provided cookies, (b) a backend proxy server, or (c) a paid third-party API.
- All file downloads go to: `Downloads/VidFlow/` on device.
