# VidFlow — All-in-One Video Downloader

> **⚠️ Codename Notice:** "VidFlow" is a temporary development codename. Branding is configurable via [`branding.json`](./branding.json) before public release.

A professional, production-ready cross-platform video downloader for Android and iOS.

## Architecture Overview

```
┌─────────────────────┐    HTTP/WebSocket    ┌─────────────────────────┐
│  React Native App   │◄────────────────────►│  Node.js + Express API  │
│  (Android / iOS)    │                      │  (Download Engine)      │
└─────────────────────┘                      └──────────┬──────────────┘
                                                        │
                                             ┌──────────▼──────────────┐
                                             │  yt-dlp + FFmpeg        │
                                             │  (Media Extraction)     │
                                             └─────────────────────────┘
```

## Project Structure

```
Video Downloader/
├── branding.json          # 🎨 Single source of truth for app branding
├── .gitignore
├── README.md
│
├── server/                # Node.js backend (download engine)
│   ├── src/
│   │   ├── index.ts       # Express + Socket.IO entry point
│   │   ├── routes/
│   │   │   ├── video.routes.ts     # /api/v1/video/* endpoints
│   │   │   └── download.routes.ts  # /api/v1/downloads/* endpoints
│   │   └── services/
│   │       └── downloadEngine.ts   # yt-dlp wrapper + platform registry
│   ├── .env.example       # Environment config template
│   ├── package.json
│   └── tsconfig.json
│
├── mobile/                # React Native app (Phase 5+)
│
├── poc/                   # Proof of Concept tests
│   ├── download-engine/
│   │   └── test-poc.ts    # PoC test suite runner
│   └── test-results/      # Test output and reports
│
├── docs/                  # Project documentation
│   ├── phase-reports/     # Phase completion reports
│   └── research/          # Research notes
│
└── scripts/               # Build, deployment, branding scripts
```

## Development Phases

| Phase | Description | Status |
|-------|-------------|--------|
| 1 | Research, architecture, feasibility | 🔄 In Progress |
| 2 | Minimal working download engine + tests | ⏳ Pending |
| 3 | Android storage + download manager | ⏳ Pending |
| 4 | iOS storage + Files integration | ⏳ Pending |
| 5 | Premium glassmorphism UI | ⏳ Pending |
| 6 | Platform connectors + error handling | ⏳ Pending |
| 7 | Automated + real-device testing | ⏳ Pending |
| 8 | Signed APK / IPA builds | ⏳ Pending |
| 9 | Official website + distribution | ⏳ Pending |

## Prerequisites

### Development Machine
- Node.js ≥ 18
- Git
- **yt-dlp** — `winget install yt-dlp`
- **FFmpeg** — `winget install ffmpeg`

### Android Development
- Java JDK 17+
- Android Studio
- Android SDK API 24+

### iOS Development (macOS only)
- Xcode 15+
- CocoaPods

## Quick Start

### Run the Download Engine Server

```bash
cd server
cp .env.example .env
npm install
npm run dev
```

### Run Phase 1 PoC Tests

```bash
cd server
npm install
npx ts-node ../poc/download-engine/test-poc.ts
```

## API Reference

```
GET  /health                            — Server health check
GET  /api/v1/video/detect?url=<url>    — Detect platform, check support
GET  /api/v1/video/info?url=<url>      — Fetch metadata + available formats
GET  /api/v1/video/platforms           — Full platform compatibility matrix

POST /api/v1/downloads                 — Start download job
GET  /api/v1/downloads/:jobId          — Poll job status
GET  /api/v1/downloads/:jobId/file     — Download completed file
DEL  /api/v1/downloads/:jobId          — Cancel job
GET  /api/v1/downloads                 — List all active jobs
```

## Security

- SSRF protection on all URL inputs
- Rate limiting (30 req/min per IP)
- Helmet security headers
- CORS restricted to approved origins
- Input validation on all endpoints
- No private URLs logged
- API secrets not exposed to mobile clients

## License & Legal

- yt-dlp: [The Unlicense](https://github.com/yt-dlp/yt-dlp/blob/master/LICENSE)
- FFmpeg: LGPLv2.1+ (dynamically linked)
- This app: Proprietary (pre-release)

See [`docs/legal/`](./docs/legal/) for full dependency license documentation.

---

*Built with Node.js, TypeScript, React Native, yt-dlp, FFmpeg*
