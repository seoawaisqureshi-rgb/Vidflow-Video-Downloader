import { Router, Request, Response } from 'express';
import { query, validationResult } from 'express-validator';
import { fetchMetadata, detectPlatform, validateUrl, PLATFORM_REGISTRY } from '../services/downloadEngine';

export const videoRoutes = Router();

/**
 * GET /api/v1/video/info?url=<video_url>
 * Fetches metadata (title, thumbnail, available formats) without downloading.
 */
videoRoutes.get(
  '/info',
  [
    query('url')
      .isString()
      .notEmpty()
      .withMessage('url query parameter is required')
      .isLength({ max: 2048 })
      .withMessage('URL too long'),
  ],
  async (req: Request, res: Response) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    const rawUrl = req.query.url as string;

    // Validate URL (SSRF protection)
    const { valid, error } = validateUrl(rawUrl);
    if (!valid) {
      return res.status(400).json({ error });
    }

    // Detect platform
    const platform = detectPlatform(rawUrl);

    try {
      const metadata = await fetchMetadata(rawUrl);
      return res.json({
        success: true,
        platform: platform?.name || 'Unknown',
        platformStatus: platform?.status || 'not_tested',
        platformNotes: platform?.notes,
        metadata,
      });
    } catch (err: any) {
      // Log minimally — don't log the full URL unnecessarily
      console.error('[VideoInfo] Error fetching metadata for platform:', platform?.name || 'unknown');
      return res.status(422).json({
        success: false,
        error: err.message || 'Failed to fetch video information.',
        platform: platform?.name || 'Unknown',
        platformStatus: platform?.status || 'not_tested',
      });
    }
  }
);

/**
 * GET /api/v1/video/detect?url=<video_url>
 * Quick platform detection without any yt-dlp calls.
 */
videoRoutes.get(
  '/detect',
  [
    query('url').isString().notEmpty().isLength({ max: 2048 }),
  ],
  (req: Request, res: Response) => {
    const errors = validationResult(req);
    if (!errors.isEmpty()) {
      return res.status(400).json({ errors: errors.array() });
    }

    const rawUrl = req.query.url as string;

    const { valid, error } = validateUrl(rawUrl);
    if (!valid) {
      return res.status(400).json({ error, supported: false });
    }

    const platform = detectPlatform(rawUrl);
    return res.json({
      success: true,
      supported: !!platform,
      platform: platform?.name || null,
      status: platform?.status || 'unsupported',
      requiresCookies: platform?.requiresCookies || false,
      notes: platform?.notes || null,
    });
  }
);

/**
 * GET /api/v1/video/platforms
 * Returns the full platform compatibility matrix.
 */
videoRoutes.get('/platforms', (_req: Request, res: Response) => {
  return res.json({
    success: true,
    platforms: PLATFORM_REGISTRY,
    legend: {
      verified: 'Tested and confirmed working',
      conditional: 'Works with caveats (auth, rate limits, etc.)',
      unsupported: 'Known not to work',
      not_tested: 'Not yet verified',
    },
  });
});
