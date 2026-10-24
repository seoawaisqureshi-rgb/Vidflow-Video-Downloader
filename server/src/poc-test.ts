/**
 * VidFlow Phase 1 — Proof of Concept: Download Engine Test
 *
 * Run from the server/ directory:
 *   cd server
 *   npx ts-node src/poc-test.ts
 */

import * as path from 'path';
import * as fs from 'fs';
import * as os from 'os';

// Import directly from server services (same project)
import {
  checkYtDlpInstalled,
  detectPlatform,
  validateUrl,
  fetchMetadata,
  downloadVideo,
  PLATFORM_REGISTRY,
  DownloadJob,
} from './services/downloadEngine';

// ─────────────────────────────────────────────
// Test Configuration
// ─────────────────────────────────────────────

const TEST_OUTPUT_DIR = path.resolve(__dirname, '../../poc/test-results/downloads');

// ─────────────────────────────────────────────
// Test Result Tracking
// ─────────────────────────────────────────────

interface TestResult {
  name: string;
  passed: boolean;
  actual: string;
  expected: string;
  duration: number;
  error?: string;
  data?: any;
}

const results: TestResult[] = [];
let passed = 0;
let failed = 0;

function log(msg: string) { console.log(msg); }

function pass(name: string, actual: string, expected: string, duration: number, data?: any) {
  log(`  ✅ PASS — ${name}`);
  log(`     Result: ${actual}`);
  if (data) log(`     Data: ${JSON.stringify(data).slice(0, 300)}`);
  results.push({ name, passed: true, actual, expected, duration, data });
  passed++;
}

function fail(name: string, actual: string, expected: string, duration: number, error?: string) {
  log(`  ❌ FAIL — ${name}`);
  log(`     Expected: ${expected}`);
  log(`     Got: ${actual}`);
  if (error) log(`     Error: ${error}`);
  results.push({ name, passed: false, actual, expected, duration, error });
  failed++;
}

// ─────────────────────────────────────────────
// Tests
// ─────────────────────────────────────────────

async function testYtDlpInstall(): Promise<boolean> {
  log('\n━━━ Test 1: yt-dlp Installation Check ━━━');
  const start = Date.now();
  const result = await checkYtDlpInstalled();
  const duration = Date.now() - start;

  if (result.installed) {
    pass('yt-dlp binary check', `v${result.version}`, 'installed', duration);
    return true;
  } else {
    fail('yt-dlp binary check', 'not installed', 'installed', duration, result.error);
    log('\n  ⚠️  Download tests will be skipped.');
    log('  Install: winget install yt-dlp');
    return false;
  }
}

async function testSSRFProtection() {
  log('\n━━━ Test 2: SSRF URL Validation ━━━');

  const shouldBlock = [
    ['http://192.168.1.1/video', 'Private 192.168.x.x'],
    ['http://10.0.0.1/video', 'Private 10.x.x.x'],
    ['http://172.16.0.1/video', 'Private 172.16.x.x'],
    ['http://localhost/admin', 'Localhost name'],
    ['http://127.0.0.1:8080', 'Loopback IP'],
    ['http://169.254.169.254/meta-data', 'AWS metadata endpoint'],
    ['ftp://files.example.com/video', 'Non-HTTP protocol'],
    ['not-a-url', 'Invalid URL'],
  ];

  for (const [url, label] of shouldBlock) {
    const start = Date.now();
    const { valid, error } = validateUrl(url);
    const duration = Date.now() - start;
    if (!valid) {
      pass(`SSRF Block: ${label}`, `blocked: ${error}`, 'rejected', duration);
    } else {
      fail(`SSRF Block: ${label}`, 'allowed', 'rejected', duration);
    }
  }

  // Valid HTTPS URL should pass
  const start = Date.now();
  const { valid } = validateUrl('https://www.youtube.com/watch?v=dQw4w9WgXcQ');
  const duration = Date.now() - start;
  if (valid) {
    pass('Valid HTTPS URL allowed', 'allowed', 'allowed', duration);
  } else {
    fail('Valid HTTPS URL allowed', 'blocked', 'allowed', duration);
  }
}

async function testPlatformDetection() {
  log('\n━━━ Test 3: Platform Detection ━━━');

  const cases: [string, string][] = [
    ['https://www.youtube.com/watch?v=test', 'YouTube'],
    ['https://youtu.be/dQw4w9WgXcQ', 'YouTube'],
    ['https://www.tiktok.com/@user/video/123', 'TikTok'],
    ['https://vm.tiktok.com/abc', 'TikTok'],
    ['https://www.instagram.com/reel/abc/', 'Instagram'],
    ['https://www.facebook.com/watch/?v=123', 'Facebook'],
    ['https://fb.watch/abc/', 'Facebook'],
    ['https://twitter.com/user/status/1', 'X / Twitter'],
    ['https://x.com/user/status/1', 'X / Twitter'],
    ['https://www.reddit.com/r/videos/comments/abc/t/', 'Reddit'],
    ['https://v.redd.it/abc', 'Reddit'],
    ['https://vimeo.com/123456', 'Vimeo'],
    ['https://www.dailymotion.com/video/x1', 'Dailymotion'],
    ['https://www.pinterest.com/pin/123/', 'Pinterest'],
    ['https://threads.net/@user/post/abc', 'Threads'],
    ['https://unknownsite999xyz.com/video', 'null'],
  ];

  for (const [url, expected] of cases) {
    const start = Date.now();
    const platform = detectPlatform(url);
    const duration = Date.now() - start;
    const actual = platform ? platform.name : 'null';

    let hostname = '';
    try { hostname = new URL(url).hostname; } catch { hostname = url; }

    if (actual === expected) {
      pass(`Detect: ${hostname}`, actual, expected, duration);
    } else {
      fail(`Detect: ${hostname}`, actual, expected, duration);
    }
  }
}

async function testMetadataFetch(ytDlpAvailable: boolean) {
  log('\n━━━ Test 4: Video Metadata Fetch (yt-dlp + internet required) ━━━');

  if (!ytDlpAvailable) {
    log('  ⏭️  Skipped — yt-dlp not installed');
    return;
  }

  // CC-licensed test video
  const testUrl = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ';
  log(`  URL: ${testUrl}`);
  log('  (Fetches metadata only — no download)');

  const start = Date.now();
  try {
    const metadata = await fetchMetadata(testUrl);
    const duration = Date.now() - start;

    if (metadata.title && metadata.id && metadata.formats.length > 0) {
      pass(
        'YouTube metadata fetch',
        `"${metadata.title}" (${metadata.formats.length} formats)`,
        'valid metadata',
        duration,
        {
          id: metadata.id,
          platform: metadata.platform,
          duration: metadata.duration,
          formatCount: metadata.formats.length,
          formats: metadata.formats.slice(0, 3).map(f => `${f.formatId}:${f.ext}:${f.quality}`),
        }
      );
    } else {
      fail('YouTube metadata fetch', 'incomplete metadata', 'title+id+formats', duration);
    }
  } catch (err: any) {
    fail('YouTube metadata fetch', 'error', 'valid metadata', Date.now() - start, err.message);
  }
}

async function testActualDownload(ytDlpAvailable: boolean) {
  log('\n━━━ Test 5: Actual Video Download (yt-dlp + ffmpeg + internet) ━━━');

  if (!ytDlpAvailable) {
    log('  ⏭️  Skipped — yt-dlp not installed');
    return;
  }

  if (!fs.existsSync(TEST_OUTPUT_DIR)) {
    fs.mkdirSync(TEST_OUTPUT_DIR, { recursive: true });
  }

  // Short CC-licensed clip — lowest quality for fast test
  const testUrl = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ';
  log(`  URL: ${testUrl}`);
  log(`  Output: ${TEST_OUTPUT_DIR}`);
  log('  Format: worst (smallest file for speed)');

  const start = Date.now();
  let lastLoggedProgress = 0;

  try {
    const filePath = await downloadVideo({
      url: testUrl,
      formatId: 'worst',
      outputDir: TEST_OUTPUT_DIR,
      onProgress: (update) => {
        if (update.progress && update.progress - lastLoggedProgress >= 20) {
          log(`  📥 ${update.progress?.toFixed(1)}% @ ${update.speed ?? '?'} — ETA ${update.eta ?? '?'}`);
          lastLoggedProgress = update.progress;
        }
      },
    });
    const duration = Date.now() - start;

    if (filePath && fs.existsSync(filePath)) {
      const stat = fs.statSync(filePath);
      const sizeMB = (stat.size / 1024 / 1024).toFixed(2);
      pass(
        'YouTube video download',
        `${sizeMB} MB file saved`,
        'file on disk',
        duration,
        { path: filePath, bytes: stat.size, durationMs: duration }
      );
      log(`\n  📁 Saved: ${filePath}`);
      log(`  📦 Size:  ${sizeMB} MB`);
      log(`  ⏱️  Time:  ${(duration / 1000).toFixed(1)}s`);
    } else {
      fail('YouTube video download', 'file not found', 'file on disk', duration);
    }
  } catch (err: any) {
    const duration = Date.now() - start;
    fail('YouTube video download', 'error', 'file on disk', duration, err.message);

    if (err.message.includes('ffmpeg') || err.message.includes('merger')) {
      log('\n  💡 FFmpeg not found. Install: winget install ffmpeg');
    }
  }
}

// ─────────────────────────────────────────────
// Report
// ─────────────────────────────────────────────

function generateReport(ytDlpVersion?: string) {
  const reportDir = path.resolve(__dirname, '../../poc/test-results');
  if (!fs.existsSync(reportDir)) fs.mkdirSync(reportDir, { recursive: true });
  const reportPath = path.join(reportDir, 'poc-report.md');

  const platformRows = PLATFORM_REGISTRY.map(p => {
    const emoji = { verified: '✅', conditional: '⚠️', unsupported: '❌', not_tested: '🔲' }[p.status];
    const auth = p.requiresCookies ? '🍪 Yes' : 'No';
    return `| ${p.name} | ${emoji} ${p.status} | ${auth} | ${p.formats.join(', ')} | ${p.notes} |`;
  }).join('\n');

  const testRows = results.map(r => {
    const emoji = r.passed ? '✅' : '❌';
    return `| ${emoji} | ${r.name} | ${r.actual.slice(0, 60)} | ${r.duration}ms |`;
  }).join('\n');

  const content = `# VidFlow — Phase 1 PoC Test Report

| Field | Value |
|-------|-------|
| Generated | ${new Date().toISOString()} |
| yt-dlp | ${ytDlpVersion ? `v${ytDlpVersion}` : '❌ Not installed'} |
| Node.js | ${process.version} |
| OS | ${os.platform()} ${os.release()} (${os.arch()}) |
| Results | **${passed} passed, ${failed} failed** |

---

## Platform Compatibility Matrix

| Platform | Status | Cookies? | Formats | Notes |
|----------|--------|----------|---------|-------|
${platformRows}

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
${testRows}

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
| Node.js | ✅ v${process.version} | None |
| Git | ✅ | None |
| yt-dlp | ${ytDlpVersion ? '✅ v' + ytDlpVersion : '❌ Missing'} | ${ytDlpVersion ? 'None' : 'winget install yt-dlp'} |
| FFmpeg | ❓ Not checked | winget install ffmpeg |
| Java JDK | ❌ Missing | Install JDK 17 for Android |
| Android SDK | ❌ Missing | Install Android Studio |
| Python | ❌ Missing | Not required (server approach) |

---

## Next Steps (Phase 2)

1. Install yt-dlp: \`winget install yt-dlp\`
2. Install FFmpeg: \`winget install ffmpeg\`
3. Re-run PoC to verify actual downloads work
4. Build Express API around the download engine
5. Initialize React Native mobile app scaffold
6. Implement real-time progress via WebSocket
`;

  fs.writeFileSync(reportPath, content, 'utf8');
  log(`\n📄 Full report: ${reportPath}`);
  return reportPath;
}

// ─────────────────────────────────────────────
// Main
// ─────────────────────────────────────────────

async function main() {
  log('╔══════════════════════════════════════════════════════════╗');
  log('║    VidFlow — Phase 1 Proof of Concept Test Suite         ║');
  log('╚══════════════════════════════════════════════════════════╝\n');
  log(`Node.js: ${process.version} | OS: ${os.platform()} ${os.arch()}`);
  log(`Time: ${new Date().toISOString()}\n`);

  const ytDlpResult = await checkYtDlpInstalled();

  await testSSRFProtection();
  await testPlatformDetection();
  await testMetadataFetch(ytDlpResult.installed);
  await testActualDownload(ytDlpResult.installed);

  log('\n╔══════════════════════════════════════════════════════════╗');
  log(`║  SUMMARY: ${String(passed).padEnd(3)} passed | ${String(failed).padEnd(3)} failed`);
  log('╚══════════════════════════════════════════════════════════╝');

  generateReport(ytDlpResult.version);

  if (!ytDlpResult.installed) {
    log('\n════════════ ACTION REQUIRED ════════════');
    log('Install yt-dlp:  winget install yt-dlp');
    log('Install FFmpeg:  winget install ffmpeg');
    log('Then re-run:     npx ts-node src/poc-test.ts');
    log('═════════════════════════════════════════');
  }

  process.exit(failed > 0 ? 1 : 0);
}

main().catch(err => {
  console.error('Fatal PoC error:', err);
  process.exit(1);
});
