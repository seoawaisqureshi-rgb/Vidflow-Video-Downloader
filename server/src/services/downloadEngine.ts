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
  signal?: AbortSignal;
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
    notes: 'Public videos work. Private groups and reels require cookies.',
    requiresAuth: true,
    requiresCookies: true,
    formats: ['mp4'],
  },
  {
    name: 'X / Twitter',
    domains: ['twitter.com', 'x.com', 'mobile.twitter.com'],
    status: 'conditional',
    notes: 'Public tweets work. Multi-video tweets require selecting the video index.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'Reddit',
    domains: ['reddit.com', 'www.reddit.com', 'v.redd.it', 'redd.it'],
    status: 'conditional',
    notes: 'Reddit native videos have separate audio and video streams; FFmpeg merges them. NSFW requires cookies.',
    requiresAuth: false,
    requiresCookies: false,
    formats: ['mp4'],
  },
  {
    name: 'Vimeo',
    domains: ['vimeo.com', 'player.vimeo.com'],
    status: 'conditional',
    notes: 'Vimeo web client requires login credentials or session cookies; direct player embeds work conditionally.',
    requiresAuth: true,
    requiresCookies: true,
    formats: ['mp4'],
  },
  {
    name: 'Dailymotion',
    domains: ['dailymotion.com', 'www.dailymotion.com', 'dai.ly'],
    status: 'conditional',
    notes: 'Public videos work. Geo-restricted content requires proxy.',
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
    /^169\.254\./,      // Link-local / Cloud metadata
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
// Binary Resolution (yt-dlp, ffmpeg, ffprobe)
// ─────────────────────────────────────────────

export function resolveBinary(name: string, envVar?: string): string {
  if (envVar && process.env[envVar]) {
    const custom = process.env[envVar]!;
    if (path.isAbsolute(custom) && fs.existsSync(custom)) return custom;
    const resolvedFromCwd = path.resolve(process.cwd(), custom);
    if (fs.existsSync(resolvedFromCwd)) return resolvedFromCwd;
    const resolvedFromDir = path.resolve(__dirname, '../../', custom);
    if (fs.existsSync(resolvedFromDir)) return resolvedFromDir;
    return custom;
  }

  const exeName = process.platform === 'win32' ? `${name}.exe` : name;
  const candidates = [
    path.resolve(__dirname, '../../bin', exeName),
    path.resolve(__dirname, '../bin', exeName),
    path.resolve(process.cwd(), 'bin', exeName),
    path.resolve(process.cwd(), 'server/bin', exeName),
    path.resolve(process.env.LOCALAPPDATA || '', 'Microsoft/WinGet/Links', exeName),
    path.resolve(process.env.ProgramFiles || '', exeName),
  ];

  for (const candidate of candidates) {
    if (fs.existsSync(candidate)) {
      return candidate;
    }
  }

  return name;
}

export function getYtDlpPath(): string {
  return resolveBinary('yt-dlp', 'YTDLP_BINARY');
}

export function getFfmpegPath(): string {
  return resolveBinary('ffmpeg', 'FFMPEG_BINARY');
}

export function getFfprobePath(): string {
  return resolveBinary('ffprobe', 'FFPROBE_BINARY');
}

export function getJsRuntimeArgs(): string[] {
  const denoBin = resolveBinary('deno', 'DENO_BINARY');
  if (fs.existsSync(denoBin)) {
    return ['--js-runtimes', `deno:${denoBin}`];
  }
  if (process.execPath && fs.existsSync(process.execPath)) {
    return ['--js-runtimes', `node:${process.execPath}`];
  }
  return [];
}

/**
 * Spawns yt-dlp and collects stdout/stderr, returning on process close.
 */
function runYtDlp(args: string[]): Promise<{ stdout: string; stderr: string; code: number }> {
  return new Promise((resolve) => {
    const bin = getYtDlpPath();
    const runtimeArgs = getJsRuntimeArgs();
    const finalArgs = [...runtimeArgs, ...args];
    const opts: SpawnOptions = { shell: false };
    const proc = spawn(bin, finalArgs, opts);

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
 * Downloads a video to local disk with full cancellation & cleanup support.
 * Returns the path of the completed file.
 */
export async function downloadVideo(options: DownloadOptions): Promise<string> {
  const { url, formatId, outputDir, onProgress, signal } = options;

  const { valid, error } = validateUrl(url);
  if (!valid) throw new Error(error);

  if (signal?.aborted) {
    throw new Error('Download aborted before start.');
  }

  // Ensure output directory exists
  if (!fs.existsSync(outputDir)) {
    fs.mkdirSync(outputDir, { recursive: true });
  }

  const ffmpegBin = getFfmpegPath();
  const ytdlpBin = getYtDlpPath();

  const args: string[] = [
    ...getJsRuntimeArgs(),
    '--no-playlist',
    '--output', path.join(outputDir, '%(title)s-%(id)s.%(ext)s'),
    '--progress',
    '--newline',
    '--no-part',
    '--write-info-json',
  ];

  if (fs.existsSync(ffmpegBin) || ffmpegBin !== 'ffmpeg') {
    args.push('--ffmpeg-location', ffmpegBin);
  }

  if (formatId) {
    args.push('--format', formatId);
  } else {
    args.push('--format', 'bestvideo[ext=mp4]+bestaudio[ext=m4a]/bestvideo+bestaudio/best');
    args.push('--merge-output-format', 'mp4');
  }

  args.push(url);

  return new Promise((resolve, reject) => {
    const proc = spawn(ytdlpBin, args, { shell: false });
    let completedFile = '';
    let stderr = '';
    let isCancelled = false;

    // Handle cancellation signal
    if (signal) {
      signal.addEventListener('abort', () => {
        isCancelled = true;
        try {
          proc.kill('SIGTERM');
          setTimeout(() => {
            if (!proc.killed) {
              try { proc.kill('SIGKILL'); } catch {}
            }
          }, 1000);
        } catch {}

        // Clean up partial artifacts
        cleanupPartialFiles(outputDir, completedFile);
        reject(new Error('Download cancelled by user.'));
      }, { once: true });
    }

    proc.stdout?.on('data', (chunk: Buffer) => {
      const lines = chunk.toString().split('\n');
      for (const line of lines) {
        const destMatch = line.match(/^\[download\] Destination:\s+(.+)$/);
        if (destMatch) completedFile = destMatch[1].trim();

        const mergeMatch = line.match(/^\[Merger\] Merging formats into "(.+)"$/);
        if (mergeMatch) completedFile = mergeMatch[1].trim();

        const alreadyMatch = line.match(/^\[download\] (.+) has already been downloaded$/);
        if (alreadyMatch) completedFile = alreadyMatch[1].trim();

        const progress = parseProgressLine(line);
        if (progress && onProgress) onProgress(progress);
      }
    });

    proc.stderr?.on('data', (chunk: Buffer) => {
      stderr += chunk.toString();
    });

    proc.on('close', (code) => {
      if (isCancelled) return;

      if (code === 0 && completedFile && fs.existsSync(completedFile)) {
        resolve(completedFile);
      } else if (code === 0) {
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
      if (isCancelled) return;
      if ((err as any).code === 'ENOENT') {
        reject(new Error(
          `yt-dlp binary not found at "${ytdlpBin}". ` +
          `Please ensure yt-dlp is installed.`
        ));
      } else {
        reject(err);
      }
    });
  });
}

function cleanupPartialFiles(outputDir: string, specificFile?: string) {
  try {
    if (specificFile && fs.existsSync(specificFile)) {
      fs.unlinkSync(specificFile);
    }
    if (fs.existsSync(outputDir)) {
      const entries = fs.readdirSync(outputDir);
      for (const entry of entries) {
        if (entry.endsWith('.part') || entry.endsWith('.ytdl')) {
          try { fs.unlinkSync(path.join(outputDir, entry)); } catch {}
        }
      }
    }
  } catch {}
}

/**
 * Checks whether yt-dlp binary is available, its path, and returns its version.
 */
export async function checkYtDlpInstalled(): Promise<{ installed: boolean; version?: string; binaryPath?: string; error?: string }> {
  const binaryPath = getYtDlpPath();
  const { stdout, stderr, code } = await runYtDlp(['--version']);
  if (code === 0) {
    return { installed: true, version: stdout.trim(), binaryPath };
  }
  if (stderr.includes('ENOENT') || stderr.includes('not found')) {
    return { installed: false, binaryPath, error: `yt-dlp binary not found at "${binaryPath}".` };
  }
  return { installed: false, binaryPath, error: stderr.slice(0, 200) };
}

/**
 * Checks whether FFmpeg binary is available, its path, and returns its version.
 */
export function checkFFmpegInstalled(): Promise<{ installed: boolean; version?: string; binaryPath?: string; error?: string }> {
  return new Promise((resolve) => {
    const binaryPath = getFfmpegPath();
    const proc = spawn(binaryPath, ['-version'], { shell: false });
    let stdout = '';
    let stderr = '';

    proc.stdout?.on('data', (d: Buffer) => { stdout += d.toString(); });
    proc.stderr?.on('data', (d: Buffer) => { stderr += d.toString(); });

    proc.on('close', (code) => {
      if (code === 0) {
        const firstLine = stdout.split('\n')[0] || '';
        const match = firstLine.match(/ffmpeg version ([^\s]+)/);
        const version = match ? match[1] : firstLine;
        resolve({ installed: true, version, binaryPath });
      } else {
        resolve({ installed: false, binaryPath, error: `Exit code ${code}` });
      }
    });

    proc.on('error', (err) => {
      resolve({ installed: false, binaryPath, error: err.message });
    });
  });
}

export interface MediaIntegrityResult {
  valid: boolean;
  duration?: number;
  format?: string;
  videoCodec?: string;
  audioCodec?: string;
  width?: number;
  height?: number;
  sizeBytes?: number;
  error?: string;
}

/**
 * Verifies downloaded media file integrity using ffprobe.
 */
export function verifyMediaIntegrity(filePath: string): Promise<MediaIntegrityResult> {
  return new Promise((resolve) => {
    if (!fs.existsSync(filePath)) {
      return resolve({ valid: false, error: 'File does not exist on disk.' });
    }

    const stat = fs.statSync(filePath);
    if (stat.size === 0) {
      return resolve({ valid: false, error: 'File exists but is empty (0 bytes).' });
    }

    const ffprobeBin = getFfprobePath();
    const args = [
      '-v', 'error',
      '-show_entries', 'format=duration,format_name,size:stream=codec_type,codec_name,width,height',
      '-of', 'json',
      filePath,
    ];

    const proc = spawn(ffprobeBin, args, { shell: false });
    let stdout = '';
    let stderr = '';

    proc.stdout?.on('data', (d: Buffer) => { stdout += d.toString(); });
    proc.stderr?.on('data', (d: Buffer) => { stderr += d.toString(); });

    proc.on('close', (code) => {
      if (code !== 0) {
        // Fallback: If ffprobe fails, but file exists and has non-zero size
        return resolve({
          valid: stat.size > 1024,
          sizeBytes: stat.size,
          error: stderr ? stderr.slice(0, 200) : 'ffprobe check failed',
        });
      }

      try {
        const parsed = JSON.parse(stdout);
        const duration = parseFloat(parsed.format?.duration || '0');
        const formatName = parsed.format?.format_name;
        const streams = parsed.streams || [];
        const videoStream = streams.find((s: any) => s.codec_type === 'video');
        const audioStream = streams.find((s: any) => s.codec_type === 'audio');

        resolve({
          valid: true,
          duration,
          format: formatName,
          videoCodec: videoStream?.codec_name,
          audioCodec: audioStream?.codec_name,
          width: videoStream?.width,
          height: videoStream?.height,
          sizeBytes: stat.size,
        });
      } catch (err: any) {
        resolve({
          valid: stat.size > 1024,
          sizeBytes: stat.size,
          error: `Failed to parse ffprobe output: ${err.message}`,
        });
      }
    });

    proc.on('error', (err) => {
      resolve({
        valid: stat.size > 1024,
        sizeBytes: stat.size,
        error: `ffprobe error: ${err.message}`,
      });
    });
  });
}
