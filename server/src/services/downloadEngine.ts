import { spawn, SpawnOptions } from 'child_process';
import * as fs from 'fs';
import * as path from 'path';
import * as os from 'os';

// ─────────────────────────────────────────────
// Platform Connector Types
// ─────────────────────────────────────────────

export type PlatformSupportStatus =
  | 'verified'        // Tested and confirmed working
  | 'conditional'     // Works with caveats (auth needed, etc.)
  | 'unsupported'     // Known to not work
  | 'not_tested';     // Not yet verified

export interface PlatformInfo {
  name: string;
  domains: string[];
  status: PlatformSupportStatus;
  notes: string;
  requiresAuth: boolean;
  requiresCookies: boolean;
  formats: string[];
}

export interface VideoMetadata {
  id: string;
  title: string;
  description?: string;
  duration?: number;       // seconds
  thumbnail?: string;      // URL
  uploader?: string;
  platform: string;
  url: string;
  formats: VideoFormat[];
  timestamp?: number;
}

export interface VideoFormat {
  formatId: string;
  ext: string;
  quality: string;
  resolution?: string;     // e.g. "1920x1080"
  height?: number;
  fps?: number;
  filesize?: number;       // bytes
  vcodec?: string;
  acodec?: string;
  hasVideo: boolean;
  hasAudio: boolean;
  note?: string;
}

export interface DownloadJob {
  jobId: string;
  url: string;
  formatId: string;
  outputPath: string;
  status: 'pending' | 'downloading' | 'processing' | 'complete' | 'failed' | 'cancelled';
  progress: number;        // 0–100
  speed?: string;          // e.g. "1.23MiB/s"
  eta?: string;            // e.g. "00:32"
  filesize?: number;
  filename?: string;
  error?: string;
  startedAt?: Date;
  completedAt?: Date;
}

export interface DownloadOptions {
  url: string;
  formatId?: string;
  outputDir: string;
  onProgress?: (job: Partial<DownloadJob>) => void;
}

// ─────────────────────────────────────────────
// Platform Registry
// ─────────────────────────────────────────────

export const PLATFORM_REGISTRY: PlatformInfo[] = [
  {
    name: 'YouTube',
    domains: ['youtube.com', 'youtu.be', 'www.youtube.com', 'm.youtube.com'],
    status: 'verified',
    notes: 'Public videos work. Age-restricted and private require cookies. High-res (4K) requires FFmpeg.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4', 'webm', 'mkv', 'm4a', 'mp3'],
  },
  {
    name: 'TikTok',
    domains: ['tiktok.com', 'www.tiktok.com', 'vm.tiktok.com'],
    status: 'conditional',
    notes: 'Public videos work. Rate-limiting on bulk downloads. Watermark removal depends on format selection.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'Instagram',
    domains: ['instagram.com', 'www.instagram.com'],
    status: 'conditional',
    notes: 'Requires login cookies for most content. Reels may work anonymously. Stories require auth.',
    requiresAuth: true,
    requiresCookies: true,
    formats: ['mp4'],
  },
  {
    name: 'Facebook',
    domains: ['facebook.com', 'www.facebook.com', 'fb.watch', 'm.facebook.com'],
    status: 'conditional',
    notes: 'Public videos work. Friend-locked or group content requires session cookies.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'X / Twitter',
    domains: ['twitter.com', 'x.com', 'www.twitter.com', 'www.x.com'],
    status: 'conditional',
    notes: 'Public tweets with video work. API changes frequent. May require cookies.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'Reddit',
    domains: ['reddit.com', 'www.reddit.com', 'v.redd.it'],
    status: 'conditional',
    notes: 'Native Reddit video (v.redd.it) works but requires FFmpeg to merge audio+video streams.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'Vimeo',
    domains: ['vimeo.com', 'www.vimeo.com', 'player.vimeo.com'],
    status: 'verified',
    notes: 'Public videos and password-protected videos (with password) work reliably.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4', 'webm'],
  },
  {
    name: 'Dailymotion',
    domains: ['dailymotion.com', 'www.dailymotion.com', 'dai.ly'],
    status: 'conditional',
    notes: 'Most public videos work. Regional restrictions may apply.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'Pinterest',
    domains: ['pinterest.com', 'www.pinterest.com', 'pin.it'],
    status: 'conditional',
    notes: 'Pin videos work if yt-dlp extractor is active. Image pins are not videos.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'Threads',
    domains: ['threads.net', 'www.threads.net'],
    status: 'not_tested',
    notes: 'yt-dlp extractor exists but not yet tested. Requires verification.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
];

// ─────────────────────────────────────────────
// URL Validation & Platform Detection
// ─────────────────────────────────────────────

/**
 * Validates that a URL is safe to process (no SSRF, no private IPs)
 */
export function validateUrl(rawUrl: string): { valid: boolean; error?: string; url?: URL } {
  let parsed: URL;
  try {
    parsed = new URL(rawUrl);
  } catch {
    return { valid: false, error: 'Invalid URL format.' };
  }

  // Only allow HTTPS (and HTTP for local testing)
  if (!['https:', 'http:'].includes(parsed.protocol)) {
    return { valid: false, error: 'Only HTTP/HTTPS URLs are allowed.' };
  }

  // Block private/internal IP ranges (SSRF protection)
  const hostname = parsed.hostname;
  const privatePatterns = [
    /^localhost$/i,
    /^127\./,
    /^10\./,
    /^172\.(1[6-9]|2\d|3[01])\./,
    /^192\.168\./,
    /^::1$/,
    /^0\.0\.0\.0$/,
    /^169\.254\./,  // Link-local
    /^fd[0-9a-f]{2}:/i, // IPv6 ULA
  ];

  for (const pattern of privatePatterns) {
    if (pattern.test(hostname)) {
      return { valid: false, error: 'Private network URLs are not permitted.' };
    }
  }

  return { valid: true, url: parsed };
}

/**
 * Identifies which platform a URL belongs to
 */
export function detectPlatform(url: string): PlatformInfo | null {
  const { valid, url: parsed } = validateUrl(url);
  if (!valid || !parsed) return null;

  const hostname = parsed.hostname.toLowerCase();

  for (const platform of PLATFORM_REGISTRY) {
    for (const domain of platform.domains) {
      if (hostname === domain || hostname.endsWith(`.${domain}`)) {
        return platform;
      }
    }
  }
  return null;
}

// ─────────────────────────────────────────────
// yt-dlp Wrapper
// ─────────────────────────────────────────────

const YTDLP_BINARY = process.env.YTDLP_BINARY || 'yt-dlp';
const FFMPEG_BINARY = process.env.FFMPEG_BINARY || 'ffmpeg';

/**
 * Spawns yt-dlp and collects stdout/stderr, returning on process close.
 */
function runYtDlp(args: string[]): Promise<{ stdout: string; stderr: string; code: number }> {
  return new Promise((resolve) => {
    const opts: SpawnOptions = { shell: false };
    const proc = spawn(YTDLP_BINARY, args, opts);

    let stdout = '';
    let stderr = '';

    proc.stdout?.on('data', (d: Buffer) => { stdout += d.toString(); });
    proc.stderr?.on('data', (d: Buffer) => { stderr += d.toString(); });

    proc.on('close', (code) => {
      resolve({ stdout, stderr, code: code ?? 1 });
    });

    proc.on('error', (err) => {
      resolve({ stdout, stderr: err.message, code: 1 });
    });
  });
}

/**
 * Fetches video metadata (formats, title, thumbnail) without downloading.
 */
export async function fetchMetadata(url: string): Promise<VideoMetadata> {
  const { valid, error } = validateUrl(url);
  if (!valid) throw new Error(error);

  const platform = detectPlatform(url);

  const { stdout, stderr, code } = await runYtDlp([
    '--dump-json',
    '--no-playlist',
    '--no-warnings',
    url,
  ]);

  if (code !== 0) {
    throw new Error(`yt-dlp metadata fetch failed: ${stderr.slice(0, 500)}`);
  }

  let raw: any;
  try {
    raw = JSON.parse(stdout);
  } catch {
    throw new Error('yt-dlp returned invalid JSON — extractor may be broken for this URL.');
  }

  // Normalize formats
  const formats: VideoFormat[] = (raw.formats || []).map((f: any): VideoFormat => ({
    formatId: f.format_id,
    ext: f.ext || 'mp4',
    quality: f.format_note || f.format || 'unknown',
    resolution: f.resolution,
    height: f.height,
    fps: f.fps,
    filesize: f.filesize || f.filesize_approx,
    vcodec: f.vcodec,
    acodec: f.acodec,
    hasVideo: f.vcodec !== 'none' && !!f.vcodec,
    hasAudio: f.acodec !== 'none' && !!f.acodec,
    note: f.format_note,
  }));

  return {
    id: raw.id,
    title: raw.title || 'Untitled',
    description: raw.description,
    duration: raw.duration,
    thumbnail: raw.thumbnail,
    uploader: raw.uploader || raw.channel,
    platform: platform?.name || raw.extractor_key || 'Unknown',
    url,
    formats,
    timestamp: raw.timestamp,
  };
}

/**
 * Parses yt-dlp stdout progress lines.
 * Example: "[download]  45.2% of  23.40MiB at    1.23MiB/s ETA 00:13"
 */
function parseProgressLine(line: string): Partial<DownloadJob> | null {
  const match = line.match(
    /\[download\]\s+([\d.]+)%\s+of\s+[\d.]+\S+\s+at\s+([\d.]+\S+\/s)\s+ETA\s+([\d:]+)/
  );
  if (!match) return null;
  return {
    progress: parseFloat(match[1]),
    speed: match[2],
    eta: match[3],
    status: 'downloading',
  };
}

/**
 * Safe filename — removes characters that break file systems.
 */
function sanitizeFilename(name: string): string {
  return name
    .replace(/[<>:"/\\|?*\x00-\x1F]/g, '_')
    .replace(/\s+/g, '_')
    .slice(0, 200);
}

/**
 * Downloads a video to local disk.
 * Returns the path of the completed file.
 */
export async function downloadVideo(options: DownloadOptions): Promise<string> {
  const { url, formatId, outputDir, onProgress } = options;

  const { valid, error } = validateUrl(url);
  if (!valid) throw new Error(error);

  // Ensure output directory exists
  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
  }

  const args: string[] = [
    '--no-playlist',
    '--ffmpeg-location', FFMPEG_BINARY,
    '--output', path.join(outputDir, '%(title)s-%(id)s.%(ext)s'),
    '--progress',
    '--newline',           // One progress line per newline — easier to parse
    '--no-part',           // Don't leave .part files on cancel
    '--write-info-json',   // Metadata sidecar
  ];

  if (formatId) {
    args.push('--format', formatId);
  } else {
    // Default: best quality with audio merged, prefer mp4
    args.push('--format', 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best');
    args.push('--merge-output-format', 'mp4');
  }

  args.push(url);

  return new Promise((resolve, reject) => {
    const proc = spawn(YTDLP_BINARY, args, { shell: false });
    let completedFile = '';
    let stderr = '';

    proc.stdout?.on('data', (chunk: Buffer) => {
      const lines = chunk.toString().split('\n');
      for (const line of lines) {
        // Detect completed file
        const destMatch = line.match(/^\[download\] Destination:\s+(.+)$/);
        if (destMatch) completedFile = destMatch[1].trim();

        const mergeMatch = line.match(/^\[Merger\] Merging formats into "(.+)"$/);
        if (mergeMatch) completedFile = mergeMatch[1].trim();

        const alreadyMatch = line.match(/^\[download\] (.+) has already been downloaded$/);
        if (alreadyMatch) completedFile = alreadyMatch[1].trim();

        // Progress events
        const progress = parseProgressLine(line);
        if (progress && onProgress) onProgress(progress);
      }
    });

    proc.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString();
    });

    proc.on('close', (code) => {
      if (code === 0 && completedFile && fs.existsSync(completedFile)) {
        resolve(completedFile);
      } else if (code === 0) {
        // Try to find the most recently modified file in outputDir
        const files = fs.readdirSync(outputDir)
          .filter(f => !f.endsWith('.info.json') && !f.endsWith('.part'))
          .map(f => ({ name: f, mtime: fs.statSync(path.join(outputDir, f)).mtime }))
          .sort((a, b) => b.mtime.getTime() - a.mtime.getTime());

        if (files.length > 0) {
          resolve(path.join(outputDir, files[0].name));
        } else {
          reject(new Error('Download completed but output file not found.'));
        }
      } else {
        reject(new Error(`yt-dlp exited with code ${code}. ${stderr.slice(0, 300)}`));
      }
    });

    proc.on('error', (err) => {
      if ((err as any).code === 'ENOENT') {
        reject(new Error(
          `yt-dlp binary not found at "${YTDLP_BINARY}". ` +
          `Please install yt-dlp: https://github.com/yt-dlp/yt-dlp#installation`
        ));
      } else {
        reject(err);
      }
    });
  });
}

/**
 * Checks whether yt-dlp binary is available and returns its version.
 */
export async function checkYtDlpInstalled(): Promise<{ installed: boolean; version?: string; error?: string }> {
  const { stdout, stderr, code } = await runYtDlp(['--version']);
  if (code === 0) {
    return { installed: true, version: stdout.trim() };
  }
  if (stderr.includes('ENOENT') || stderr.includes('not found')) {
    return { installed: false, error: 'yt-dlp binary not found on PATH.' };
  }
  return { installed: false, error: stderr.slice(0, 200) };
}
