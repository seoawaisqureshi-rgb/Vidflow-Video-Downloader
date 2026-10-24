import 'dotenv/config';
import express from 'express';
import cors from 'cors';
import helmet from 'helmet';
import morgan from 'morgan';
import rateLimit from 'express-rate-limit';
import { createServer } from 'http';
import { Server as SocketIOServer } from 'socket.io';

import { videoRoutes } from './routes/video.routes';
import { downloadRoutes } from './routes/download.routes';
import { checkYtDlpInstalled } from './services/downloadEngine';

const app = express();
const httpServer = createServer(app);

// ─────────────────────────────────────────────
// Socket.IO — real-time progress updates
// ─────────────────────────────────────────────

export const io = new SocketIOServer(httpServer, {
  cors: {
    origin: process.env.CORS_ORIGINS?.split(',') || ['http://localhost:8081'],
    methods: ['GET', 'POST'],
  },
});

io.on('connection', (socket) => {
  console.log(`[Socket] Client connected: ${socket.id}`);
  socket.on('disconnect', () => {
    console.log(`[Socket] Client disconnected: ${socket.id}`);
  });
});

// ─────────────────────────────────────────────
// Security Middleware
// ─────────────────────────────────────────────

// Helmet — sets secure HTTP headers
app.use(helmet({
  contentSecurityPolicy: {
    directives: {
      defaultSrc: ["'self'"],
      scriptSrc: ["'none'"],
      objectSrc: ["'none'"],
    },
  },
}));

// CORS — only allow approved origins
const allowedOrigins = process.env.CORS_ORIGINS?.split(',') || ['http://localhost:8081'];
app.use(cors({
  origin: (origin, callback) => {
    // Allow requests with no origin (e.g., mobile apps, Postman dev testing)
    if (!origin || allowedOrigins.includes(origin)) {
      callback(null, true);
    } else {
      callback(new Error(`CORS: Origin ${origin} not allowed`));
    }
  },
  credentials: true,
}));

// Rate limiting — prevent abuse
const limiter = rateLimit({
  windowMs: parseInt(process.env.RATE_LIMIT_WINDOW_MS || '60000'),
  max: parseInt(process.env.RATE_LIMIT_MAX_REQUESTS || '30'),
  standardHeaders: true,
  legacyHeaders: false,
  message: { error: 'Too many requests. Please slow down.' },
});
app.use('/api/', limiter);

// Body parsing
app.use(express.json({ limit: '10kb' }));  // Small limit — we only accept URLs
app.use(express.urlencoded({ extended: false, limit: '10kb' }));

// Logging (only in development)
if (process.env.NODE_ENV !== 'production') {
  app.use(morgan('dev'));
}

// ─────────────────────────────────────────────
// Routes
// ─────────────────────────────────────────────

// Health check
app.get('/health', (_req, res) => {
  res.json({
    status: 'ok',
    version: '1.0.0',
    timestamp: new Date().toISOString(),
  });
});

// API routes
app.use('/api/v1/video', videoRoutes);
app.use('/api/v1/downloads', downloadRoutes);

// 404 handler
app.use((_req, res) => {
  res.status(404).json({ error: 'Endpoint not found' });
});

// Global error handler
app.use((err: Error, _req: express.Request, res: express.Response, _next: express.NextFunction) => {
  console.error('[Error]', err.message);
  res.status(500).json({
    error: process.env.NODE_ENV === 'production'
      ? 'Internal server error'
      : err.message,
  });
});

// ─────────────────────────────────────────────
// Startup
// ─────────────────────────────────────────────

const PORT = parseInt(process.env.PORT || '3000');

async function startServer() {
  // Verify yt-dlp is available
  const ytDlp = await checkYtDlpInstalled();
  if (!ytDlp.installed) {
    console.warn('⚠️  yt-dlp is not installed or not on PATH!');
    console.warn('   Download endpoints will fail. Install yt-dlp first.');
    console.warn('   Windows: winget install yt-dlp');
    console.warn('   yt-dlp path:', process.env.YTDLP_BINARY || 'yt-dlp (system PATH)');
  } else {
    console.log(`✅ yt-dlp v${ytDlp.version} detected`);
  }

  httpServer.listen(PORT, () => {
    console.log(`\n🚀 VidFlow Server running on http://localhost:${PORT}`);
    console.log(`   Environment: ${process.env.NODE_ENV || 'development'}`);
    console.log(`   API: http://localhost:${PORT}/api/v1/`);
    console.log(`   Health: http://localhost:${PORT}/health\n`);
  });
}

startServer().catch(err => {
  console.error('Failed to start server:', err);
  process.exit(1);
});
