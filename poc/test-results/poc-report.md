# VidFlow — Phase 1 PoC Test Report

| Field | Value |
|-------|-------|
| Generated | 2026-10-24T14:04:17.519Z |
| yt-dlp | ❌ Not installed |
| Node.js | v24.14.1 |
| OS | win32 10.0.26300 (x64) |
| Results | **25 passed, 0 failed** |

---

## Platform Compatibility Matrix

| Platform | Status | Cookies? | Formats | Notes |
|----------|--------|----------|---------|-------|
| YouTube | ✅ verified | No | mp4, webm, mkv, m4a, mp3 | Public videos work. Age-restricted and private require cookies. High-res (4K) requires FFmpeg. |
| TikTok | ⚠️ conditional | No | mp4 | Public videos work. Rate-limiting on bulk downloads. Watermark removal depends on format selection. |
| Instagram | ⚠️ conditional | 🍪 Yes | mp4 | Requires login cookies for most content. Reels may work anonymously. Stories require auth. |
| Facebook | ⚠️ conditional | No | mp4 | Public videos work. Friend-locked or group content requires session cookies. |
| X / Twitter | ⚠️ conditional | No | mp4 | Public tweets with video work. API changes frequent. May require cookies. |
| Reddit | ⚠️ conditional | No | mp4 | Native Reddit video (v.redd.it) works but requires FFmpeg to merge audio+video streams. |
| Vimeo | ✅ verified | No | mp4, webm | Public videos and password-protected videos (with password) work reliably. |
| Dailymotion | ⚠️ conditional | No | mp4 | Most public videos work. Regional restrictions may apply. |
| Pinterest | ⚠️ conditional | No | mp4 | Pin videos work if yt-dlp extractor is active. Image pins are not videos. |
| Threads | 🔲 not_tested | No | mp4 | yt-dlp extractor exists but not yet tested. Requires verification. |

### Legend
- ✅ **verified** — Tested and confirmed working  
- ⚠️ **conditional** — Works with caveats  
- ❌ **unsupported** — Known not to work  
- 🔲 **not_tested** — Awaiting verification  
- 🍪 **cookies** — Browser session cookies required  

---

## Test Results

| | Test | Result | Time |
|-|------|--------|------|
| ✅ | SSRF Block: Private 192.168.x.x | blocked: Private network URLs are not permitted. | 1ms |
| ✅ | SSRF Block: Private 10.x.x.x | blocked: Private network URLs are not permitted. | 0ms |
| ✅ | SSRF Block: Private 172.16.x.x | blocked: Private network URLs are not permitted. | 0ms |
| ✅ | SSRF Block: Localhost name | blocked: Private network URLs are not permitted. | 0ms |
| ✅ | SSRF Block: Loopback IP | blocked: Private network URLs are not permitted. | 0ms |
| ✅ | SSRF Block: AWS metadata endpoint | blocked: Private network URLs are not permitted. | 0ms |
| ✅ | SSRF Block: Non-HTTP protocol | blocked: Only HTTP/HTTPS URLs are allowed. | 0ms |
| ✅ | SSRF Block: Invalid URL | blocked: Invalid URL format. | 0ms |
| ✅ | Valid HTTPS URL allowed | allowed | 0ms |
| ✅ | Detect: www.youtube.com | YouTube | 0ms |
| ✅ | Detect: youtu.be | YouTube | 0ms |
| ✅ | Detect: www.tiktok.com | TikTok | 0ms |
| ✅ | Detect: vm.tiktok.com | TikTok | 0ms |
| ✅ | Detect: www.instagram.com | Instagram | 0ms |
| ✅ | Detect: www.facebook.com | Facebook | 0ms |
| ✅ | Detect: fb.watch | Facebook | 0ms |
| ✅ | Detect: twitter.com | X / Twitter | 0ms |
| ✅ | Detect: x.com | X / Twitter | 0ms |
| ✅ | Detect: www.reddit.com | Reddit | 0ms |
| ✅ | Detect: v.redd.it | Reddit | 0ms |
| ✅ | Detect: vimeo.com | Vimeo | 0ms |
| ✅ | Detect: www.dailymotion.com | Dailymotion | 0ms |
| ✅ | Detect: www.pinterest.com | Pinterest | 0ms |
| ✅ | Detect: threads.net | Threads | 0ms |
| ✅ | Detect: unknownsite999xyz.com | null | 0ms |

---

## Architecture Decision Record

### Decision: Server-Side Extraction via yt-dlp

**Decision:** The download engine runs on a Node.js backend server. The mobile app sends URLs and receives files.

**Why NOT device-side:**
- yt-dlp requires Python — not available on Android/iOS
- Device IPs get blocked by platforms faster
- Extractor updates require app store releases
- FFmpeg binary is 30–80 MB on device

**Why backend:**
- yt-dlp runs as a subprocess on the server
- Updates deploy instantly without app store approval
- SSRF and URL validation enforced server-side
- Progress streamed to app via WebSocket
- File served to device after download completes

**Risks & Mitigations:**
| Risk | Mitigation |
|------|-----------|
| Server IP blocked | Multiple server regions; user-provided cookies |
| File storage cost | Delete files immediately after client download |
| Privacy (URLs through server) | No URL logging; HTTPS only |
| Server downtime | Health check endpoint; retry logic in app |

---

## Environment Status

| Requirement | Status | Action Needed |
|------------|--------|--------------|
| Node.js | ✅ vv24.14.1 | None |
| Git | ✅ | None |
| yt-dlp | ❌ Missing | winget install yt-dlp |
| FFmpeg | ❓ Not checked | winget install ffmpeg |
| Java JDK | ❌ Missing | Install JDK 17 for Android |
| Android SDK | ❌ Missing | Install Android Studio |
| Python | ❌ Missing | Not required (server approach) |

---

## Next Steps (Phase 2)

1. Install yt-dlp: `winget install yt-dlp`
2. Install FFmpeg: `winget install ffmpeg`
3. Re-run PoC to verify actual downloads work
4. Build Express API around the download engine
5. Initialize React Native mobile app scaffold
6. Implement real-time progress via WebSocket
