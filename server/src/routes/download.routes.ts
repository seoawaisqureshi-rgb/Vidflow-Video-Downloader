import { Router, Request, Response } from 'express';
import { body, param, validationResult } from 'express-validator';
import * as path from 'path';
import * as fs from 'fs';
import { v4 as uuidv4 } from 'uuid';
import { downloadVideo, validateUrl, DownloadJob } from '../services/downloadEngine';
import { io } from '../index';

export const downloadRoutes = Router();

// In-memory job store & abort controllers — Phase 1 (will move to Redis/DB in Phase 3)
const activeJobs = new Map<string, DownloadJob>();
const jobControllers = new Map<string, AbortController>();

const DOWNLOAD_DIR = path.resolve(process.env.DOWNLOAD_DIR || './downloads');

/**
 * POST /api/v1/downloads
 * Creates a new download job.
 * Body: { url: string, formatId?: string }
 */
downloadRoutes.post(
  '/',
  [
    body('url')
      .isString()
      .notEmpty()
      .isLength({ max: 2048 })
      .withMessage('url is required'),
    body('formatId')
      .optional()
      .isString()
      .isLength({ max: 50 }),
  ],
  async (req: Request, res: Response) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    const { url, formatId } = req.body;

    // SSRF check
    const { valid, error } = validateUrl(url);
    if (!valid) {
      return res.status(400).json({ error });
    }

    // Check concurrent download limit
    const running = [...activeJobs.values()].filter(
      j => j.status === 'downloading' || j.status === 'processing'
    ).length;

    const maxConcurrent = parseInt(process.env.MAX_CONCURRENT_DOWNLOADS || '3');
    if (running >= maxConcurrent) {
      return res.status(429).json({
        error: `Download queue full (max ${maxConcurrent} concurrent). Please wait.`,
        activeJobs: running,
      });
    }

    // Create job & controller
    const jobId = uuidv4();
    const controller = new AbortController();
    jobControllers.set(jobId, controller);

    const job: DownloadJob = {
      jobId,
      url,
      formatId: formatId || 'best',
      outputPath: DOWNLOAD_DIR,
      status: 'pending',
      progress: 0,
      startedAt: new Date(),
    };

    activeJobs.set(jobId, job);

    // Respond immediately with jobId — download runs in background
    res.status(202).json({
      success: true,
      jobId,
      message: 'Download job created. Connect via WebSocket for progress updates.',
      socketEvent: `download:${jobId}`,
    });

    // Start download asynchronously with cancellation signal
    runDownloadJob(job, controller);
    return;
  }
);

/**
 * GET /api/v1/downloads/:jobId
 * Returns current status of a download job.
 */
downloadRoutes.get(
  '/:jobId',
  [param('jobId').isUUID()],
  (req: Request, res: Response) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    const job = activeJobs.get(req.params.jobId);
    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    return res.json({ success: true, job: sanitizeJob(job) });
  }
);

/**
 * GET /api/v1/downloads/:jobId/file
 * Streams the completed file to the client.
 * File is deleted after transfer (clean up storage).
 */
downloadRoutes.get(
  '/:jobId/file',
  [param('jobId').isUUID()],
  (req: Request, res: Response) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    const job = activeJobs.get(req.params.jobId);
    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }
    if (job.status !== 'complete') {
      return res.status(409).json({ error: `Job not complete yet (status: ${job.status})` });
    }
    if (!job.filename || !fs.existsSync(job.filename)) {
      return res.status(410).json({ error: 'File no longer available' });
    }

    const filename = path.basename(job.filename);
    const stat = fs.statSync(job.filename);

    res.setHeader('Content-Disposition', `attachment; filename="${encodeURIComponent(filename)}"`);
    res.setHeader('Content-Length', stat.size);
    res.setHeader('Content-Type', 'video/mp4');

    const stream = fs.createReadStream(job.filename);
    stream.pipe(res);

    stream.on('close', () => {
      // Clean up file after transfer
      try {
        fs.unlinkSync(job.filename!);
        const infoJson = job.filename!.replace(/\.[^.]+$/, '.info.json');
        if (fs.existsSync(infoJson)) fs.unlinkSync(infoJson);
        activeJobs.delete(job.jobId);
        jobControllers.delete(job.jobId);
      } catch (err) {
        console.error('[Download] Cleanup error:', err);
      }
    });

    return;
  }
);

/**
 * DELETE /api/v1/downloads/:jobId
 * Cancels a pending or in-progress job and cleans up child processes and files.
 */
downloadRoutes.delete(
  '/:jobId',
  [param('jobId').isUUID()],
  (req: Request, res: Response) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    const job = activeJobs.get(req.params.jobId);
    if (!job) {
      return res.status(404).json({ error: 'Job not found' });
    }

    // Abort process if running
    const controller = jobControllers.get(job.jobId);
    if (controller) {
      controller.abort();
      jobControllers.delete(job.jobId);
    }

    job.status = 'cancelled';
    emitJobUpdate(job);
    activeJobs.delete(job.jobId);
    return res.json({ success: true, message: 'Job cancelled and process terminated' });
  }
);

/**
 * GET /api/v1/downloads
 * Lists all active/recent jobs.
 */
downloadRoutes.get('/', (_req: Request, res: Response) => {
  const jobs = [...activeJobs.values()].map(sanitizeJob);
  return res.json({ success: true, jobs, total: jobs.length });
});

// ─────────────────────────────────────────────
// Internal helpers
// ─────────────────────────────────────────────

/**
 * Strips sensitive data before returning job to client.
 */
function sanitizeJob(job: DownloadJob): Partial<DownloadJob> {
  return {
    jobId: job.jobId,
    status: job.status,
    progress: job.progress,
    speed: job.speed,
    eta: job.eta,
    filename: job.filename ? path.basename(job.filename) : undefined,
    filesize: job.filesize,
    error: job.error,
    startedAt: job.startedAt,
    completedAt: job.completedAt,
    // Do NOT return the full URL or server-side file path
  };
}

/**
 * Runs a download job and emits progress via WebSocket.
 */
async function runDownloadJob(job: DownloadJob, controller: AbortController) {
  try {
    job.status = 'downloading';
    emitJobUpdate(job);

    const filePath = await downloadVideo({
      url: job.url,
      formatId: job.formatId !== 'best' ? job.formatId : undefined,
      outputDir: job.outputPath,
      signal: controller.signal,
      onProgress: (update) => {
        Object.assign(job, update);
        emitJobUpdate(job);
      },
    });

    job.status = 'complete';
    job.progress = 100;
    job.filename = filePath;
    job.completedAt = new Date();

    const stat = fs.statSync(filePath);
    job.filesize = stat.size;

    emitJobUpdate(job);
    console.log(`[Download] Job ${job.jobId} complete: ${path.basename(filePath)}`);
  } catch (err: any) {
    if (err.message.includes('cancelled') || err.message.includes('aborted')) {
      job.status = 'cancelled';
    } else {
      job.status = 'failed';
    }
    job.error = err.message;
    emitJobUpdate(job);
    console.error(`[Download] Job ${job.jobId} ended:`, err.message);
  } finally {
    jobControllers.delete(job.jobId);
  }
}

function emitJobUpdate(job: DownloadJob) {
  io.emit(`download:${job.jobId}`, sanitizeJob(job));
  // Also emit to a general channel for the downloads list
  io.emit('downloads:update', sanitizeJob(job));
}
