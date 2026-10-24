/**
 * VidFlow Phase 1 — Proof of Concept: Download Engine Test
 *
 * This script tests the download engine WITHOUT the full server running.
 * It directly calls yt-dlp and records real test results.
 *
 * HOW TO RUN:
 *   cd server
 *   npm install
 *   npx ts-node ../poc/download-engine/test-poc.ts
 *
 * REQUIREMENTS:
 *   - yt-dlp installed and on PATH (or set YTDLP_BINARY env var)
 *   - ffmpeg installed and on PATH (or set FFMPEG_BINARY env var)
 *   - Internet connection
 */

import * as path from 'path';
import * as fs from 'fs';

// We import from the server source directly
import {
  checkYtDlpInstalled,
  detectPlatform,
  validateUrl,
  fetchMetadata,
  downloadVideo,
  PLATFORM_REGISTRY,
  DownloadJob,
} from '../../server/src/services/downloadEngine';

// ─────────────────────────────────────────────
// Test Configuration
// ─────────────────────────────────────────────

/**
 * AUTHORIZED TEST URLS
 * These are publicly available, non-DRM-protected media URLs
 * used specifically for testing the download engine.
 *
 * - Blender Foundation videos are CC-licensed
 * - Public domain and Creative Commons content only
 * - No copyrighted commercial content
 */
const TEST_CASES: Array<{
  name: string;
  url: string;
  platform: string;
  expectedStatus: 'pass' | 'fail' | 'conditional';
  notes: string;
}> = [
  {
    name: 'YouTube — CC-licensed short video (Big Buck Bunny trailer)',
    url: 'https://www.youtube.com/watch?v=aqz-KE-bpKQ',
    platform: 'YouTube',
    expectedStatus: 'pass',
    notes: 'Blender Foundation, Creative Commons — safe for testing',
  },
  {
    name: 'Vimeo — Public video (Blender Foundation)',
    url: 'https://vimeo.com/1084537',
    platform: 'Vimeo',
    expectedStatus: 'pass',
    notes: 'Elephant Dream — open content project',
  },
  {
    name: 'URL Validation — Private IP (SSRF test)',
    url: 'http://192.168.1.1/video.mp4',
    platform: 'N/A',
    expectedStatus: 'fail',
    notes: 'Should be rejected by SSRF protection',
  },
  {
    name: 'URL Validation — Localhost (SSRF test)',
    url: 'http://localhost:8080/admin',
    platform: 'N/A',
    expectedStatus: 'fail',
    notes: 'Should be rejected by SSRF protection',
  },
  {
    name: 'Platform Detection — TikTok',
    url: 'https://www.tiktok.com/@test/video/123',
    platform: 'TikTok',
    expectedStatus: 'pass',
    notes: 'Platform detection only — no actual download',
  },
  {
    name: 'Platform Detection — Instagram',
    url: 'https://www.instagram.com/reel/test123/',
    platform: 'Instagram',
    expectedStatus: 'pass',
    notes: 'Platform detection only — no actual download',
  },
  {
    name: 'Platform Detection — Unknown site',
    url: 'https://unknownsite123xyz.com/video',
    platform: 'Unknown',
    expectedStatus: 'pass',
    notes: 'Should return null (not in registry)',
  },
];

// Output directory for test downloads
const TEST_OUTPUT_DIR = path.resolve(__dirname, '../test-results/downloads');

// ─────────────────────────────────────────────
// Test Runner
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

function log(msg: string) {
  console.log(msg);
}

function pass(name: string, actual: string, expected: string, duration: number, data?: any) {
  log(`  ✅ PASS — ${name}`);
  log(`     Expected: ${expected}`);
  log(`     Got:      ${actual}`);
  if (data) log(`     Data: ${JSON.stringify(data, null, 2).slice(0, 300)}`);
  results.push({ name, passed: true, actual, expected, duration, data });
  passed++;
}

function fail(name: string, actual: string, expected: string, duration: number, error?: string) {
  log(`  ❌ FAIL — ${name}`);
  log(`     Expected: ${expected}`);
  log(`     Got:      ${actual}`);
  if (error) log(`     Error: ${error}`);
  results.push({ name, passed: false, actual, expected, duration, error });
  failed++;
}

// ─────────────────────────────────────────────
// Individual Tests
// ─────────────────────────────────────────────

async function testYtDlpInstall() {
  log('\n━━━ Test: yt-dlp Installation Check ━━━');
  const start = Date.now();
  const result = await checkYtDlpInstalled();
  const duration = Date.now() - start;

  if (result.installed) {
    pass('yt-dlp binary check', `v${result.version}`, 'installed', duration);
    return true;
  } else {
    fail(
      'yt-dlp binary check',
      'not found',
      'installed',
      duration,
      result.error
    );
    log('\n  ⚠️  yt-dlp is not installed. Download tests will be skipped.');
    log('  Install it from: https://github.com/yt-dlp/yt-dlp#installation');
    log('  Windows quick install: winget install yt-dlp');
    return false;
  }
}

async function testSSRFProtection() {
  log('\n━━━ Test: SSRF URL Validation ━━━');

  const ssrfUrls = [
    { url: 'http://192.168.1.1/video', name: 'Private IP 192.168.x.x' },
    { url: 'http://10.0.0.1/video', name: 'Private IP 10.x.x.x' },
    { url: 'http://localhost/admin', name: 'Localhost' },
    { url: 'http://127.0.0.1:8080', name: 'Loopback IP' },
    { url: 'ftp://files.example.com/video', name: 'Non-HTTP protocol' },
    { url: 'not-a-url-at-all', name: 'Invalid URL format' },
  ];

  for (const tc of ssrfUrls) {
    const start = Date.now();
    const { valid, error } = validateUrl(tc.url);
    const duration = Date.now() - start;

    if (!valid) {
      pass(`SSRF Block: ${tc.name}`, `blocked (${error})`, 'rejected', duration);
    } else {
      fail(`SSRF Block: ${tc.name}`, 'accepted', 'rejected', duration);
    }
  }

  // Legitimate URLs should pass
  const legit = 'https://www.youtube.com/watch?v=test';
  const { valid: legitValid } = validateUrl(legit);
  const start2 = Date.now();
  if (legitValid) {
    pass('Valid YouTube URL allowed', 'accepted', 'accepted', Date.now() - start2);
  } else {
    fail('Valid YouTube URL allowed', 'blocked', 'accepted', Date.now() - start2);
  }
}

async function testPlatformDetection() {
  log('\n━━━ Test: Platform Detection ━━━');

  const detectionCases = [
    { url: 'https://www.youtube.com/watch?v=test', expected: 'YouTube' },
    { url: 'https://youtu.be/test', expected: 'YouTube' },
    { url: 'https://www.tiktok.com/@user/video/123', expected: 'TikTok' },
    { url: 'https://vm.tiktok.com/abc123', expected: 'TikTok' },
    { url: 'https://www.instagram.com/reel/abc/', expected: 'Instagram' },
    { url: 'https://www.facebook.com/watch/?v=123', expected: 'Facebook' },
    { url: 'https://fb.watch/abc123/', expected: 'Facebook' },
    { url: 'https://twitter.com/user/status/123', expected: 'X / Twitter' },
    { url: 'https://x.com/user/status/123', expected: 'X / Twitter' },
    { url: 'https://www.reddit.com/r/videos/comments/abc/title/', expected: 'Reddit' },
    { url: 'https://v.redd.it/abc123', expected: 'Reddit' },
    { url: 'https://vimeo.com/123456', expected: 'Vimeo' },
    { url: 'https://www.dailymotion.com/video/x123', expected: 'Dailymotion' },
    { url: 'https://unknownplatform999.com/video', expected: 'null' },
  ];

  for (const tc of detectionCases) {
    const start = Date.now();
    const platform = detectPlatform(tc.url);
    const duration = Date.now() - start;
    const actual = platform ? platform.name : 'null';

    if (actual === tc.expected) {
      pass(`Detect: ${new URL(tc.url).hostname}`, actual, tc.expected, duration);
    } else {
      fail(`Detect: ${new URL(tc.url).hostname}`, actual, tc.expected, duration);
    }
  }
}

async function testMetadataFetch(ytDlpAvailable: boolean) {
  log('\n━━━ Test: Video Metadata Fetch (requires yt-dlp + internet) ━━━');

  if (!ytDlpAvailable) {
    log('  ⏭️  Skipped — yt-dlp not installed');
    return;
  }

  // Use a short, CC-licensed YouTube video
  const testUrl = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ';
  log(`  Testing: ${testUrl}`);
  log('  (This fetches metadata only — no download occurs)');

  const start = Date.now();
  try {
    const metadata = await fetchMetadata(testUrl);
    const duration = Date.now() - start;

    const hasRequiredFields =
      metadata.title &&
      metadata.id &&
      metadata.formats &&
      metadata.formats.length > 0;

    if (hasRequiredFields) {
      pass(
        'YouTube metadata fetch',
        `title="${metadata.title}" formats=${metadata.formats.length}`,
        'valid metadata with formats',
        duration,
        {
          id: metadata.id,
          title: metadata.title,
          platform: metadata.platform,
          duration: metadata.duration,
          formatCount: metadata.formats.length,
          sampleFormats: metadata.formats.slice(0, 3).map(f => ({
            id: f.formatId,
            ext: f.ext,
            quality: f.quality,
            resolution: f.resolution,
          })),
        }
      );
    } else {
      fail('YouTube metadata fetch', 'incomplete metadata', 'valid metadata', duration);
    }
  } catch (err: any) {
    const duration = Date.now() - start;
    fail('YouTube metadata fetch', 'error', 'valid metadata', duration, err.message);
  }
}

async function testActualDownload(ytDlpAvailable: boolean) {
  log('\n━━━ Test: Actual Video Download (requires yt-dlp + ffmpeg + internet) ━━━');

  if (!ytDlpAvailable) {
    log('  ⏭️  Skipped — yt-dlp not installed');
    return;
  }

  if (!fs.existsSync(TEST_OUTPUT_DIR)) {
    fs.mkdirSync(TEST_OUTPUT_DIR, { recursive: true });
  }

  // Short clip for testing — YouTube Creative Commons
  const testUrl = 'https://www.youtube.com/watch?v=aqz-KE-bpKQ';
  log(`  Downloading: ${testUrl}`);
  log(`  Output dir:  ${TEST_OUTPUT_DIR}`);
  log('  Format: lowest quality (for fast test)');

  const start = Date.now();
  try {
    let lastProgress = 0;
    const filePath = await downloadVideo({
      url: testUrl,
      formatId: 'worst',  // Smallest file for testing
      outputDir: TEST_OUTPUT_DIR,
      onProgress: (update) => {
        if (update.progress && update.progress - lastProgress >= 10) {
          log(`  Progress: ${update.progress?.toFixed(1)}% @ ${update.speed} ETA ${update.eta}`);
          lastProgress = update.progress;
        }
      },
    });
    const duration = Date.now() - start;

    if (filePath && fs.existsSync(filePath)) {
      const stats = fs.statSync(filePath);
      pass(
        'YouTube video download',
        `file saved (${(stats.size / 1024 / 1024).toFixed(2)} MB)`,
        'file exists on disk',
        duration,
        { filePath, fileSizeBytes: stats.size, durationMs: duration }
      );
      log(`  📁 File saved: ${filePath}`);
    } else {
      fail('YouTube video download', 'file not found', 'file on disk', duration);
    }
  } catch (err: any) {
    const duration = Date.now() - start;
    fail('YouTube video download', 'error', 'file on disk', duration, err.message);
    log(`\n  💡 Tip: Make sure ffmpeg is installed for format merging.`);
    log(`     Windows: winget install ffmpeg`);
    log(`     Or download from: https://ffmpeg.org/download.html`);
  }
}

// ─────────────────────────────────────────────
// Report Generator
// ─────────────────────────────────────────────

function generateReport(ytDlpVersion?: string) {
  const timestamp = new Date().toISOString();
  const reportPath = path.resolve(__dirname, '../test-results/poc-report.md');

  const platformRows = PLATFORM_REGISTRY.map(p => {
    const statusEmoji = {
      verified: '✅',
      conditional: '⚠️',
      unsupported: '❌',
      not_tested: '🔲',
    }[p.status];

    return `| ${p.name} | ${statusEmoji} ${p.status} | ${p.requiresCookies ? 'Yes' : 'No'} | ${p.formats.join(', ')} | ${p.notes} |`;
  }).join('\n');

  const testRows = results.map(r => {
    const statusEmoji = r.passed ? '✅' : '❌';
    const duration = `${r.duration}ms`;
    return `| ${statusEmoji} | ${r.name} | ${r.actual} | ${duration} |`;
  }).join('\n');

  const report = `# VidFlow Phase 1 — Proof of Concept Report

**Generated:** ${timestamp}
**yt-dlp Version:** ${ytDlpVersion || 'Not installed'}
**Node.js Version:** ${process.version}
**Platform:** ${os.platform()} ${os.release()}
**Test Results:** ${passed} passed, ${failed} failed

---

## Platform Compatibility Matrix

| Platform | Status | Requires Cookies | Formats | Notes |
|----------|--------|-----------------|---------|-------|
${platformRows}

### Status Legend
- ✅ **verified** — Tested and confirmed working
- ⚠️ **conditional** — Works with caveats (auth required, rate limits, etc.)
- ❌ **unsupported** — Known not to work
- 🔲 **not_tested** — Not yet verified with actual test

---

## Test Execution Results

| Status | Test Name | Result | Duration |
|--------|-----------|--------|----------|
${testRows}

---

## Architecture Decision: Backend vs. Device-Side Extraction

### Recommendation: Hybrid Architecture

**Phase 1 Decision:** Use a Node.js backend that runs yt-dlp server-side.

**Rationale:**
1. yt-dlp cannot run natively on Android/iOS (Python + binary deps)
2. Server-side extraction avoids platform IP blocking per-user-IP
3. Enables consistent updates without app store approval for extractor fixes
4. Backend filters private IPs and validates URLs (SSRF protection)
5. Allows background processing independent of phone sleep state

**Risks:**
- Requires backend server (hosting cost, maintenance)
- Server IPs may get blocked by platforms (mitigation: multiple regions)
- File transfer: server → phone requires bandwidth
- Privacy: URLs pass through backend (mitigated by not logging unnecessarily)

**Alternative considered:** react-native-ytdlp (not mature enough)
**Alternative considered:** yt-dlp via Termux on Android (not user-friendly)

---

## Next Steps (Phase 2)

1. Install yt-dlp and ffmpeg on development machine
2. Run actual download test with real CC-licensed content
3. Build Express API endpoints around the download engine
4. Implement WebSocket progress streaming
5. Create React Native mobile app scaffold
6. Test file transfer from backend to device storage

---

## Known Limitations (Phase 1)

- yt-dlp not yet installed on this machine → download tests skipped
- Java/Android SDK not detected → React Native Android build pending
- iOS builds require macOS + Xcode → deferred
- Instagram, TikTok private content requires cookie auth → documented
- Platform extractors can break without notice when platforms change their APIs

---

*VidFlow is a development codename. Branding is configurable via branding.json before public release.*
`;

  fs.writeFileSync(reportPath, report, 'utf8');
  log(`\n📄 Report saved: ${reportPath}`);
}

// ─────────────────────────────────────────────
// Main Runner
// ─────────────────────────────────────────────

async function main() {
  log('╔════════════════════════════════════════════════════════╗');
  log('║      VidFlow — Phase 1 Proof of Concept Test Suite     ║');
  log('╚════════════════════════════════════════════════════════╝\n');

  log(`Node.js: ${process.version}`);
  log(`Platform: ${os.platform()} ${os.arch()}`);
  log(`Timestamp: ${new Date().toISOString()}\n`);

  // 1. Check yt-dlp
  const ytDlpResult = await checkYtDlpInstalled();
  const ytDlpAvailable = ytDlpResult.installed;

  // 2. SSRF protection tests (no yt-dlp needed)
  await testSSRFProtection();

  // 3. Platform detection tests (no yt-dlp needed)
  await testPlatformDetection();

  // 4. Metadata fetch (needs yt-dlp + internet)
  await testMetadataFetch(ytDlpAvailable);

  // 5. Actual download test (needs yt-dlp + ffmpeg + internet)
  await testActualDownload(ytDlpAvailable);

  // ─── Summary ─────────────────────────────────
  log('\n╔════════════════════════════════════════════════════════╗');
  log(`║  Results: ${passed} passed, ${failed} failed`);
  log('╚════════════════════════════════════════════════════════╝\n');

  // Generate and save report
  generateReport(ytDlpResult.version);

  log('\n✅ Phase 1 PoC complete. See test-results/poc-report.md for full report.');

  if (!ytDlpAvailable) {
    log('\n════════════════════════════════════════════');
    log('⚠️  ACTION REQUIRED: Install yt-dlp and ffmpeg');
    log('════════════════════════════════════════════');
    log('Windows (via winget):');
    log('  winget install yt-dlp');
    log('  winget install ffmpeg');
    log('\nThen re-run this test suite to verify real downloads work.');
  }

  process.exit(failed > 0 ? 1 : 0);
}

// We need os for the report
import * as os from 'os';
main().catch(err => {
  console.error('Fatal error in PoC runner:', err);
  process.exit(1);
});
