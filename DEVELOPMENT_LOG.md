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

## Known Remaining Issues

| Issue | Status |
|-------|--------|
| Instagram Stories | Not fixable — requires login cookie |
| Facebook Stories | Not fixable — requires login |
| yt-dlp pause (DASH) | Partial — UI pauses, yt-dlp subprocess runs to completion |
| Threads login posts | Only public posts work |
| Speed during FFmpeg merge | Shows 0 KB/s (no progress from FFmpeg) |

---

*Last updated: 2026-10-26*
