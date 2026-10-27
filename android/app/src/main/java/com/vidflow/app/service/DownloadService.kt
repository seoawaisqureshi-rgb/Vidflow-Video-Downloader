package com.vidflow.app.service

import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import com.vidflow.app.VidFlowApplication
import com.vidflow.app.data.downloader.DownloadProgress
import com.vidflow.app.data.downloader.DownloadResult
import com.vidflow.app.data.downloader.YtDlpJobs
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.vidflow.app.notifications.NotificationHelper
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

// ── Shared download state ────────────────────────────────────────────────────

sealed class ServiceState {
    object Idle : ServiceState()
    data class Downloading(
        val percent: Int,
        val speedKBps: Long,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = -1L,
        /** True during the merge step: UI shows "Merging..." and no speed. */
        val isMerging: Boolean = false,
    ) : ServiceState()
    data class Paused(
        val percent: Int,
        val bytesDownloaded: Long = 0L,
        val totalBytes: Long = -1L,
    ) : ServiceState()
    data class Success(val filePath: String) : ServiceState()
    data class Error(val message: String) : ServiceState()
    object Cancelled : ServiceState()
}

/** A single download entry in the queue */
data class DownloadEntry(
    val id: String,
    val title: String,
    val state: ServiceState,
)

data class DownloadRequest(
    val info: VideoInfo,
    val format: VideoFormat,
    val downloadUrl: String,
)

/** A paused yt-dlp job: everything resume needs to restart it. */
private data class PausedJob(
    val request: DownloadRequest,
    val dbId: Long,
    val notifId: Int,
)

// ── Foreground Download Service ──────────────────────────────────────────────

class DownloadService : Service() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private lateinit var notifMgr: NotificationManager
    private val activeCount = AtomicInteger(0)
    private val stopMutex = Mutex()

    override fun onCreate() {
        super.onCreate()
        NotificationHelper.createChannels(this)
        notifMgr = getSystemService(NotificationManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {

        if (intent?.action == ACTION_PAUSE) {
            val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
            if (id != null) {
                pausedIds.add(id)
                // Real pause (brief 3.1): kill the yt-dlp process tree.
                // .part files are deliberately kept for resume.
                YtDlpJobs.pause(id)
                _downloads.update { current ->
                    current[id]?.let { entry ->
                        val s = entry.state
                        val paused = if (s is ServiceState.Downloading)
                            ServiceState.Paused(s.percent, s.bytesDownloaded, s.totalBytes)
                        else ServiceState.Paused(0)
                        current + (id to entry.copy(state = paused))
                    } ?: current
                }
                Log.d(TAG, "Paused download $id")
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_RESUME) {
            val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
            if (id != null) {
                pausedIds.remove(id)
                YtDlpJobs.clearPaused(id)
                val pausedJob = pausedRequests.remove(id)
                if (pausedJob != null) {
                    // yt-dlp pause killed the process: resume means re-running
                    // the same command with --continue on the same template,
                    // so it continues from the kept .part files, not zero.
                    val relaunchNotifId = pausedJob.notifId
                    downloadNotifIds[id] = relaunchNotifId
                    startForeground(
                        relaunchNotifId,
                        NotificationHelper.progressNotification(
                            this, pausedJob.request.info.title, 0, 0, 0L, -1L,
                        ),
                    )
                    activeCount.incrementAndGet()
                    val job = serviceScope.launch {
                        processDownload(id, relaunchNotifId, pausedJob.request, pausedJob.dbId)
                    }
                    downloadJobs[id] = job
                    Log.d(TAG, "Resumed yt-dlp download $id from .part files")
                } else {
                    // Direct download: the engine loop was only held, so
                    // removing the id from pausedIds unblocks it.
                    _downloads.update { current ->
                        current[id]?.let { entry ->
                            val s = entry.state
                            val resuming = if (s is ServiceState.Paused)
                                ServiceState.Downloading(s.percent, 0, s.bytesDownloaded, s.totalBytes)
                            else entry.state
                            current + (id to entry.copy(state = resuming))
                        } ?: current
                    }
                    Log.d(TAG, "Resumed download $id")
                }
            }
            return START_NOT_STICKY
        }

        if (intent?.action == ACTION_CANCEL) {
            val id = intent.getStringExtra(EXTRA_DOWNLOAD_ID)
            if (id != null) {
                cancelledIds.add(id)
                pausedIds.remove(id)  // remove from paused too
                pausedRequests.remove(id)
                downloadJobs.remove(id)
                // Kill the yt-dlp process for this download if running.
                // The job itself is NOT cancelled: processDownload must run
                // to its tail to clean up state and the notification.
                YtDlpJobs.cancel(id)
                _downloads.update { current ->
                    current + (id to (current[id]?.copy(state = ServiceState.Cancelled)
                        ?: DownloadEntry(id, "", ServiceState.Cancelled)))
                }
            } else {
                // Cancel all
                cancelledIds.addAll(pendingRequests.keys)
                // Pending (not yet started) requests must not start later.
                pendingRequests.clear()
                pendingRequests.keys.forEach { YtDlpJobs.cancel(it) }
                pausedRequests.keys.forEach { YtDlpJobs.cancel(it) }
                pausedRequests.clear()
                _downloads.update { current ->
                    current.mapValues { (_, entry) -> entry.copy(state = ServiceState.Cancelled) }
                }
            }
            return START_NOT_STICKY
        }

        val downloadId = intent?.getStringExtra(EXTRA_DOWNLOAD_ID) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        val request = pendingRequests.remove(downloadId) ?: run {
            stopSelf(startId)
            return START_NOT_STICKY
        }

        // Show foreground notification immediately (required within 5s on Android 12+)
        val notifId = NOTIF_BASE_ID + activeCount.incrementAndGet()
        downloadNotifIds[downloadId] = notifId
        startForeground(
            notifId,
            NotificationHelper.progressNotification(this, request.info.title, 0, 0, 0L, -1L),
        )

        val job = serviceScope.launch {
            processDownload(downloadId, notifId, request)
        }
        downloadJobs[downloadId] = job

        return START_NOT_STICKY
    }

    private suspend fun processDownload(
        downloadId: String,
        notifId: Int,
        request: DownloadRequest,
        existingDbId: Long? = null,
    ) {
        val repository = (applicationContext as VidFlowApplication).repository

        _downloads.update { current ->
            current + (downloadId to DownloadEntry(downloadId, request.info.title, ServiceState.Downloading(0, 0)))
        }

        val (dbId, result) = repository.startDownload(
            info = request.info,
            format = request.format,
            downloadUrl = request.downloadUrl,
            downloadId = downloadId,
            existingRecordId = existingDbId,
        ) { progress: DownloadProgress ->
            if (cancelledIds.contains(downloadId)) return@startDownload
            // While paused: block the callback (OkHttp stream is halted in DownloadEngine)
            // We still update UI with paused state
            if (pausedIds.contains(downloadId)) return@startDownload

            val pct = progress.percent.coerceIn(0, 99)
            val speedKBps = progress.speedBps / 1024
            val state = ServiceState.Downloading(
                pct, speedKBps, progress.bytesDownloaded, progress.totalBytes,
                isMerging = progress.isMerging,
            )
            _downloads.update { current ->
                current + (downloadId to DownloadEntry(downloadId, request.info.title, state))
            }
            notifMgr.notify(
                notifId,
                NotificationHelper.progressNotification(
                    this@DownloadService, request.info.title, pct, speedKBps,
                    progress.bytesDownloaded, progress.totalBytes,
                ),
            )
        }

        if (result is DownloadResult.Paused) {
            // Keep the entry + .part files; resume relaunches this request.
            pausedRequests[downloadId] = PausedJob(request, dbId, notifId)
            notifMgr.cancel(notifId)
            val pausedEntry = _downloads.value[downloadId]
            val pausedState = (pausedEntry?.state as? ServiceState.Downloading)?.let {
                ServiceState.Paused(it.percent, it.bytesDownloaded, it.totalBytes)
            } ?: ServiceState.Paused(0)
            _downloads.update { current ->
                current + (downloadId to DownloadEntry(downloadId, request.info.title, pausedState))
            }
            downloadNotifIds.remove(downloadId)
            stopMutex.withLock {
                if (activeCount.decrementAndGet() == 0 && pendingRequests.isEmpty()) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return
        }

        // Show 100% briefly before switching to result state
        val last = _downloads.value[downloadId]?.state
        val lastBytes = (last as? ServiceState.Downloading)?.bytesDownloaded
            ?: (last as? ServiceState.Paused)?.bytesDownloaded
            ?: 0L
        val lastTotal = (last as? ServiceState.Downloading)?.totalBytes
            ?: (last as? ServiceState.Paused)?.totalBytes
            ?: -1L
        _downloads.update { current ->
            current + (downloadId to DownloadEntry(
                downloadId,
                request.info.title,
                ServiceState.Downloading(100, 0, lastBytes, if (lastTotal > 0) lastTotal else lastBytes),
            ))
        }
        delay(300)

        val finalState = when (result) {
            is DownloadResult.Success -> {
                notifMgr.cancel(notifId)
                notifMgr.notify(
                    NOTIF_BASE_ID + 1000 + notifId,
                    NotificationHelper.completeNotification(this@DownloadService, request.info.title),
                )
                ServiceState.Success(result.filePath)
            }
            is DownloadResult.Error -> {
                notifMgr.cancel(notifId)
                notifMgr.notify(
                    NOTIF_BASE_ID + 2000 + notifId,
                    NotificationHelper.errorNotification(this@DownloadService, result.message),
                )
                ServiceState.Error(result.message)
            }
            is DownloadResult.Cancelled -> {
                notifMgr.cancel(notifId)
                ServiceState.Cancelled
            }
            is DownloadResult.Paused -> {
                notifMgr.cancel(notifId)
                ServiceState.Paused(
                    (last as? ServiceState.Downloading)?.percent
                        ?: (last as? ServiceState.Paused)?.percent
                        ?: 0,
                    lastBytes,
                    lastTotal,
                )
            }
        }

        _downloads.update { current ->
            current + (downloadId to DownloadEntry(downloadId, request.info.title, finalState))
        }
        cancelledIds.remove(downloadId)
        downloadJobs.remove(downloadId)

        // Auto-dismiss Success entries after 5 seconds so main screen stays clean
        if (finalState is ServiceState.Success) {
            delay(5_000)
            _downloads.update { it - downloadId }
        }

        downloadNotifIds.remove(downloadId)

        stopMutex.withLock {
            if (activeCount.decrementAndGet() == 0 && pendingRequests.isEmpty()) {
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val TAG = "DownloadService"
        const val ACTION_CANCEL  = "com.vidflow.CANCEL_DOWNLOAD"
        const val ACTION_PAUSE   = "com.vidflow.PAUSE_DOWNLOAD"
        const val ACTION_RESUME  = "com.vidflow.RESUME_DOWNLOAD"
        const val EXTRA_DOWNLOAD_ID = "download_id"
        private const val NOTIF_BASE_ID = 1000

        /** Map of all active/recent downloads — observed by ViewModel */
        private val _downloads = MutableStateFlow<Map<String, DownloadEntry>>(emptyMap())
        val downloads: StateFlow<Map<String, DownloadEntry>> = _downloads.asStateFlow()

        /** Backwards-compat single state (last download state) */
        val state: StateFlow<ServiceState> get() = MutableStateFlow(
            _downloads.value.values.lastOrNull()?.state ?: ServiceState.Idle
        )

        private val pendingRequests  = ConcurrentHashMap<String, DownloadRequest>()
        internal val cancelledIds    = ConcurrentHashMap.newKeySet<String>()
        /** Paused yt-dlp jobs waiting for resume (request + history row + notif). */
        private val pausedRequests   = ConcurrentHashMap<String, PausedJob>()
        internal val pausedIds       = ConcurrentHashMap.newKeySet<String>()
        private val downloadNotifIds = ConcurrentHashMap<String, Int>()
        private val downloadJobs     = ConcurrentHashMap<String, Job>()
        private var notifIdCounter   = AtomicInteger(0)

        fun start(context: Context, request: DownloadRequest): String {
            val id = "dl_${System.currentTimeMillis()}"
            pendingRequests[id] = request
            // Immediately put an entry in _downloads so HomeScreen shows it at once
            _downloads.update { current ->
                current + (id to DownloadEntry(id, request.info.title, ServiceState.Downloading(0, 0)))
            }
            val intent = Intent(context, DownloadService::class.java).apply {
                putExtra(EXTRA_DOWNLOAD_ID, id)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
            return id
        }

        fun pause(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_PAUSE
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }

        fun dismiss(downloadId: String) {
            _downloads.update { it - downloadId }
        }

        fun resume(context: Context, downloadId: String) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_RESUME
                putExtra(EXTRA_DOWNLOAD_ID, downloadId)
            }
            context.startService(intent)
        }

        fun cancel(context: Context, downloadId: String? = null) {
            val intent = Intent(context, DownloadService::class.java).apply {
                action = ACTION_CANCEL
                downloadId?.let { putExtra(EXTRA_DOWNLOAD_ID, it) }
            }
            context.startService(intent)
        }

        fun getStateForId(id: String): ServiceState =
            _downloads.value[id]?.state ?: ServiceState.Idle

        fun resetState() {
            // No-op for backwards compatibility — state auto-cleans after 5s
        }
    }
}
