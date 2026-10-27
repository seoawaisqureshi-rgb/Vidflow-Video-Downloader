# VidFlow — Complete Development Log

> **App:** VidFlow Video Downloader (Android)
> **Package:** com.vidflow.app
> **APK:** android/app/build/outputs/apk/debug/app-debug.apk

---

## Project Structure

```
android/app/src/main/java/com/vidflow/app/
├── MainActivity.kt              # Share intent handler
├── Navigation.kt                # Bottom nav (Download, Folder, History)
├── VidFlowApplication.kt        # App init (yt-dlp, FFmpeg, Repository)
├── data/
│   ├── DownloadRepository.kt    # Central download coordinator
│   ├── db/                      # Room DB (DownloadRecord, DownloadDao)
│   ├── downloader/
│   │   └── DownloadEngine.kt    # OkHttp direct stream downloader
│   └── extractor/
│       ├── VideoExtractor.kt    # Master extractor — routes to platform extractors
│       ├── YoutubeExtractor.kt  # YouTube (InnerTube API + yt-dlp fallback)
│       └── PlatformExtractors.kt # TikTok, IG, FB, Threads, Twitter, Reddit, Vimeo, etc.
├── service/
│   └── DownloadService.kt       # Foreground service — manages all downloads
├── notifications/
│   └── NotificationHelper.kt   # Progress + complete + error notifications
├── ui/
│   ├── home/
│   │   ├── HomeScreen.kt        # Main UI
│   │   └── HomeViewModel.kt     # State manager
│   ├── history/HistoryScreen.kt
│   ├── folder/FolderScreen.kt
│   └── components/              # GlassCard, GradientButton, ProgressBar, etc.
└── theme/Theme.kt               # Dark mode, Violet+Cyan palette
```

---

## Features Built

### UI / Design
- Dark mode glassmorphism (Violet + Cyan)
- GlassCard, GradientButton, DownloadProgressBar, ShimmerBox
- Bottom nav: Download / Folder / History
- AppHeader with active download count badge
- VideoInfoCard — thumbnail, title, uploader, duration
- FormatSelectionCard — quality selector chips
- ActiveDownloadCard — progress, pause/resume/cancel/dismiss
- SupportedPlatformsRow — FlowRow wrap (no scroll)
- ClipboardBanner — auto-detect & paste
- ErrorBanner — styled errors

### Download Engine (OkHttp)
- Platform-specific headers per platform
- Scoped storage (Android 10+) + legacy fallback
- 32KB buffer for fast streaming
- Progress every 250ms (bytes + speed)
- Pause: stream blocks in loop. Cancel: deletes partial file

### Download Service (Foreground)
- Multi-download via StateFlow map
- downloadJobs map to cancel individual coroutines
- Auto-dismiss Success entries after 5 seconds
- Notifications: progress / complete / error

### Download Repository
- MP3: downloadWithYtDlpAudio() — --extract-audio --audio-format mp3
- HD/DASH: downloadWithYtDlp() — yt-dlp + FFmpeg merge
- Direct: DownloadEngine.download() — OkHttp stream
- Room DB: insert on start, progress updates, complete/fail on done

### Platform Extractors

| Platform    | Method                                      |
|-------------|---------------------------------------------|
| YouTube     | InnerTube API (instant) + yt-dlp fallback   |
| TikTok      | HTTP scrape + yt-dlp race                   |
| Instagram   | HTTP scrape → App API → Embed → yt-dlp      |
| Facebook    | HTTP scrape + yt-dlp race                   |
| Threads     | Embed scrape → yt-dlp (Instagram UA)        |
| Twitter/X   | FxTwitter → VxTwitter → Syndication APIs    |
| Reddit      | Reddit JSON API (fallback_url + HLS)        |
| Vimeo       | Player config API                           |
| Dailymotion | Embed + player API                          |
| Pinterest   | oEmbed + HTML scrape                        |
| Generic     | Direct .mp4/.webm/.mp3 URLs                 |

---

## Bugs Fixed

### Round 1
| Bug | Fix |
|-----|-----|
| Analysis 40-50s slow | InnerTube instant; yt-dlp 2.5s delayed fallback |
| Multiple downloads broken | Parallel support via _downloads StateFlow map |
| Progress bar no size text | Added fmtBytes(downloaded) / fmtBytes(total) |
| History not on main screen | DB-backed activeDownloads flow |
| Auto-paste not working | onClipboardDetected() via LifecycleEventObserver |

### Round 2 (Latest)
| Bug | Fix |
|-----|-----|
| YouTube MP3 fails | downloadWithYtDlpAudio() with --extract-audio --audio-format mp3 |
| Download start delay | DownloadService.start() immediately inserts into StateFlow |
| Pause button doesn't work | Pause loop fixed (if+while nested). downloadJobs map for cancel |
| Analysis still slow | InnerTube instant, yt-dlp 2.5s delay. Timeouts: IG/Threads 25s |
| Threads video fails | Instagram User-Agent in tryYtDlpThreads(), best selector |
| Instagram format error | parseYtDlpInstagramSafe() — uses direct URL or best selector |
| History tab re-analysis | lastAnalyzedUrl in HomeViewModel, skips if same URL |
| Completed stays on screen | Auto-dismiss Success after 5s in DownloadService |
| Platform chips scroll | FlowRow wrap instead of horizontal scroll |
| Slow buffer | 8KB → 32KB buffer. Report interval 300ms → 250ms |
| MP3 wrong path | All MP3 now needsYtDlp=true + bestaudio/best selector |

---

## Architecture

### Download Flow
```
URL paste → fetchInfo() → VideoExtractor → HTTP fast path → yt-dlp fallback
         → formats shown → user taps download
         → DownloadService.start() → immediately shows card in UI
         → DownloadRepository routes:
             MP3 → downloadWithYtDlpAudio()
             DASH/HD → downloadWithYtDlp()
             Direct → DownloadEngine.download()
         → Progress → Room DB + Notification + UI
         → Complete → auto-dismiss after 5s
```

### Format Routing
```kotlin
when {
    isMp3              → downloadWithYtDlpAudio()  // --extract-audio mp3
    format.needsYtDlp  → downloadWithYtDlp()        // FFmpeg DASH merge
    youtube+blankUrl   → downloadWithYtDlp()        // legacy YT HD
    else               → DownloadEngine.download()  // OkHttp direct
}
```

### State Flow
```
DownloadService._downloads (StateFlow<Map<String, DownloadEntry>>)
    → HomeViewModel.activeDownloads
        → HomeScreen (Compose UI)
```

---

## Build Info

```
Min SDK:  24 (Android 7.0)
Target:   35 (Android 15)
Stack:    Gradle 9.1 + Kotlin + Jetpack Compose
Java:     17 (Microsoft JDK)
APK Size: ~120MB (yt-dlp + FFmpeg bundled)
```

Build command:
```powershell
$env:JAVA_HOME = "C:\Program Files\Microsoft\jdk-17.0.20.101-hotspot"
$env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
cd android
.\gradlew.bat assembleDebug
```

---

## Session 2026-10-27: VidMate Parity & Core Bug Fixes

### 1. MP3 Audio Download Crash Fix (Scoped Storage)
- **Root Cause**: `MediaStore.Audio.Media.EXTERNAL_CONTENT_URI` throws `java.lang.IllegalArgumentException: Primary directory Download not allowed for content://media/external_primary/audio/media; allowed directories are [Alarms, Audiobooks, Music, Notifications, Podcasts, Recordings, Ringtones]` on Android 10+ when attempting to save audio under `Download/VidFlow`.
- **Resolution**:
  - In `DownloadRepository.kt` and `DownloadEngine.kt`, routed audio files to `Environment.DIRECTORY_MUSIC + "/VidFlow"`.
  - Added multi-stage fallback (Downloads media store fallback, app-specific external storage file fallback).

### 2. Four Core Bug Fixes
- **Bug 1: Link re-analyzed from scratch when returning to download screen**
  - Added `isUrlCurrentlyDownloading(...)` and tracked queued download URLs in `HomeViewModel.kt`.
  - Inhibited clipboard re-analysis if URL matches any active or queued download.
- **Bug 2: Instagram login screen layout**
  - Updated `LoginWebViewScreen.kt` with modern mobile Chrome User-Agent (`Mozilla/5.0 ... Chrome/128.0.0.0 Mobile Safari/537.36`).
  - Disabled extraneous scrollbars (`isVerticalScrollBarEnabled = false`), enabled third-party cookies, and wired `onLoginSuccess` to auto-return to download.
- **Bug 3: Threads videos without login**
  - Upgraded `ThreadsExtractor` in `PlatformExtractors.kt` with `facebookexternalhit/1.1` crawler UA and desktop Chrome fallback headers.
  - Implemented multi-tier JSON regex hydration parsing (`playback_url`, `video_versions`, `video_dash_manifest`) and anonymous yt-dlp fallback.
- **Bug 4: Samsung My Files & File Manager chooser**
  - Created `FileManagerHelper.kt` targeting Samsung My Files (`com.sec.android.app.myfiles`) explicitly if installed.
  - Provided persistent preference caching and a reset option in Settings.

### 3. VidMate Parity & UI Overhaul (Strict VidFlow Identity & On-Device Architecture)
- **WhatsApp Status Saver Screen**: WhatsApp Status scanner with 2-step user guide, media preview dialog, and one-tap save.
- **Built-in Player & MiniPlayer**: `MediaPlayerManager` with ExoPlayer, floating `MiniPlayerBar` above navigation, and full-screen player dialog.
- **My Files Screen**: Tabs (`All`, `Downloading`, `Completed`, `Failed`), search within downloads, real-time speed/progress, pause/resume/cancel.
- **Library Screen**: Segmented into Downloaded, Local Device media, and Private Space vault protected by a 4-digit PIN lock.
- **Settings Screen**: Video quality defaults, Wi-Fi only mode, language preference, file manager reset, feedback & about.
- **Search Screen**: YouTube search integration, quick chips, and direct download actions.

### 4. Senior Android Engineering Overhaul (VidMate In-App Browser & Performance Engine)
- **YouTube Ultra-Fast Analysis (<300ms) & Full Quality Tiering**:
  - Implemented parallel YouTube oEmbed fallback with a 3.5s timeout on InnerTube API to eliminate the 46s stall.
  - Generates comprehensive format suite automatically: 1080p HD, 720p HD, 480p, 360p, MP3 (320 kbps High Quality), and MP3 (128 kbps Standard).
  - YT-DLP engine dynamically binds DASH video+audio streams upon user selection for robust download execution.
- **In-App Social Media Browsing & Stream Sniffing (VidMate Architecture)**:
  - **Facebook**: Resolved blank white page by enabling `setSupportMultipleWindows(true)` and implementing `WebChromeClient.onCreateWindow(...)` to redirect popups into the main WebView. Third-party cookies and modern Chrome UA enabled smooth login and in-app feed browsing.
  - **Instagram**: Intercepted external application schemes (`instagram://`, etc.) so the page stays strictly in VidFlow's in-app WebView without jumping to native apps or showing blank screens.
  - **Pinterest**: Fixed OAuth redirect handling and blank screens via DOM storage, database, and multiple windows support. Intercepts `v.pinimg.com` media streams directly into download picker.
  - **Threads**: Blocked deep link intents to external Threads app (`threads://` or universal links) via `shouldOverrideUrlLoading`; pages now load completely inside VidFlow.
  - **Active Media Sniffing**: Floating download button detects loaded `.mp4`, `.m3u8`, `fbcdn.net`, `cdninstagram.com`, and `v.pinimg.com` streams with automatic video & audio extraction options in `QualityPickerDialog`.
- **ExoPlayer Local File Playback Fix in My Files**:
  - Identified root cause in `BuiltInPlayer.kt`: string paths starting with `/` were parsed as raw URIs (`Uri.parse(uri)`) which resulted in null schemes, causing ExoPlayer playback failure.
  - Fixed with `Uri.fromFile(File(uri))` for local storage paths, providing valid `file://` scheme to ExoPlayer.
  - Fixed `CompletedFileCard` play trigger in `MyFilesScreen.kt` to fall back to `record.url` if `record.filePath` is temporarily unpopulated.
- **Download Execution Reliability**:
  - Updated `HomeViewModel.startDownload(...)` with explicit `overrideFormat` parameter and fallback to `info.formats.firstOrNull()`.
  - Prevented silent no-ops when download button is pressed from dialog or floating action button.

### 5. Definitive 5-Issue Social Media In-App Browsing & Crash Resolution
- **Issue 1: Facebook Blank Page**:
  - Replaced legacy mobile URL `https://m.facebook.com/` (which returned HTTP 400 Bad Request to mobile webview) with official responsive endpoint `https://www.facebook.com/`.
  - Disabled `setSupportMultipleWindows(false)` to prevent Chromium window-detach blanking on OAuth and feed interactions.
- **Issue 2: Instagram "Do Nothing" on Click & Login Blank Screen**:
  - Root cause of blank screen on login: `WebChromeClient.onCreateWindow` was assigning the same source `WebView` to `transport.webView`, causing Chromium to blank out the frame. Fixed by setting `setSupportMultipleWindows(false)` and routing any unexpected `onCreateWindow` requests safely through a temporary transport `WebView` into the main viewer.
  - Root cause of "do nothing": custom app schemes (`instagram://`) had no fallback handling. Added proper intent parsing and fallback navigation.
  - Standardized User-Agent to official Chrome Mobile format (`Android 10; K`) to eliminate Instagram bot-detection interstitials.
- **Issue 3: Pinterest Blank Screen After Login**:
  - Eliminated the popup window collision during OAuth login redirects via safe single-window architecture and temporary transport routing.
  - Ensured third-party cookies and DOM storage remain active throughout login transitions.
- **Issue 4: Threads Launching External App**:
  - Root cause: `shouldOverrideUrlLoading` returned `false` for HTTP/HTTPS requests, allowing Android OS Digital Asset Links to hijack `threads.net` and launch the external Threads native app.
  - Fix: Added explicit social platform interception (`isSocialApp`) that forces navigation through `view.loadUrl(urlStr)` and returns `true`, commanding Android OS to keep all browsing strictly inside VidFlow.
- **Issue 5: TikTok Crash on "Open TikTok" Button**:
  - Root cause: Tapping "Open TikTok" on the webpage executed JavaScript `window.open("snssdk1233://...")`, triggering `onCreateWindow` where passing the source view caused an immediate Chromium native crash (SIGSEGV / Parent already has view).
  - Fix: Replaced with safe temporary transport `WebView` and strict scheme interception, preventing any crash and keeping the user inside the browser.
  - Added TikTok CDN stream sniffing (`tiktokcdn.com`, `byteoversea.com`, `ibytedtos.com`) for direct video downloads.

---

*Last updated: 2026-10-27*
