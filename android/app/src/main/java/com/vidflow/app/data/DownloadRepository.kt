package com.vidflow.app.data

import android.content.ContentValues
import android.content.Context
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import com.vidflow.app.data.db.AppDatabase
import com.vidflow.app.data.db.DownloadDao
import com.vidflow.app.data.db.DownloadRecord
import com.vidflow.app.data.downloader.DownloadEngine
import com.vidflow.app.data.downloader.DownloadProgress
import com.vidflow.app.data.downloader.DownloadResult
import com.vidflow.app.data.downloader.MediaMuxerMerger
import com.vidflow.app.data.downloader.YtDlpJobs
import com.vidflow.app.data.extractor.VideoExtractor
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.vidflow.app.data.session.SessionCookies
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

class DownloadRepository(context: Context) {

    private val appContext = context.applicationContext

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .followRedirects(true)
        .followSslRedirects(true)
        .build()

    private val extractor = VideoExtractor(httpClient, appContext)
    private val engine = DownloadEngine(appContext, httpClient)
    private val dao: DownloadDao = AppDatabase.getInstance(appContext).downloadDao()

    // ── Extraction ────────────────────────────────────────────────────────────

    suspend fun fetchVideoInfo(url: String): VideoExtractor.Result {
        return withContext(Dispatchers.IO) { extractor.extract(url) }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    /**
     * @param existingRecordId when resuming a paused yt-dlp job, reuse the
     *   same history row instead of inserting a duplicate.
     */
    suspend fun startDownload(
        info: VideoInfo,
        format: VideoFormat,
        downloadUrl: String,
        downloadId: String = "",
        existingRecordId: Long? = null,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): Pair<Long, DownloadResult> = withContext(Dispatchers.IO) {
        val id = existingRecordId ?: dao.insert(
            DownloadRecord(
                url = info.url, title = info.title, thumbnail = info.thumbnail,
                platform = info.platform, quality = format.quality, ext = format.ext,
                filePath = null, fileSize = null, duration = info.duration,
                status = DownloadStatus.DOWNLOADING, progress = 0,
            ),
        )
        // Resume floor: a resumed download starts from the stored progress,
        // so the bar never drops back to 0 on resume.
        var lastPct = existingRecordId?.let { dao.getById(it)?.progress } ?: 0
        val sink: suspend (DownloadProgress) -> Unit = { p ->
            // Global monotonic clamp: displayed progress never decreases.
            val clamped = p.percent.coerceIn(0, 100).coerceAtLeast(lastPct)
            lastPct = clamped
            onProgress(if (clamped == p.percent) p else p.copy(percent = clamped))
        }

        // Resolve download URL for Pinterest
        val resolvedUrl = if (info.platform == Platform.PINTEREST) {
            refreshPinterestUrl(info.url, downloadUrl)
        } else {
            downloadUrl
        }

        val isAudioOnly = !format.hasVideo && format.hasAudio
        val isMp3 = format.ext.lowercase() == "mp3" || format.quality.contains("MP3", ignoreCase = true)

        val result = when {
            // MP3 / Audio extraction always goes through yt-dlp with --extract-audio
            isMp3 -> {
                downloadWithYtDlpAudio(info, id, downloadId, sink)
            }
            // Explicitly marked as needing yt-dlp (DASH merge, HD, etc.)
            format.needsYtDlp -> {
                val selector = format.ytDlpFormatSelector.ifEmpty {
                    if (format.hasVideo && !format.hasAudio) "${format.formatId}+bestaudio/bestvideo+bestaudio"
                    else if (!format.hasVideo && format.hasAudio) format.formatId
                    else format.formatId.ifEmpty { "bestvideo+bestaudio/best" }
                }
                downloadWithYtDlp(info, selector, format, id, downloadId, sink)
            }
            // YouTube with blank URL (legacy path)
            info.platform == Platform.YOUTUBE && downloadUrl.isBlank() -> {
                val selector = format.ytDlpFormatSelector.ifEmpty {
                    if (format.hasVideo && !format.hasAudio)
                        "${format.formatId}+bestaudio[ext=m4a]/${format.formatId}+bestaudio/bestvideo+bestaudio"
                    else format.formatId.ifEmpty { "bestvideo+bestaudio/best" }
                }
                downloadWithYtDlp(info, selector, format, id, downloadId, sink)
            }
            // Direct HTTP download (fast path via OkHttp, Range-resumable),
            // with one automatic fresh-URL retry on expired CDN links.
            else -> downloadDirectWithRefresh(
                info, format, resolvedUrl, downloadId.ifEmpty { id.toString() }, id, sink,
            )
        }

        when (result) {
            is DownloadResult.Success -> dao.markCompleted(
                id, DownloadStatus.COMPLETED,
                result.filePath, result.fileSize, System.currentTimeMillis(),
            )
            is DownloadResult.Error -> dao.updateError(id, DownloadStatus.FAILED, result.message)
            is DownloadResult.Cancelled -> {
                dao.updateError(id, DownloadStatus.CANCELLED, "Cancelled by user")
                // Cancel is the only action that deletes partial data.
                YtDlpJobs.deletePartFiles(appContext, downloadId.ifEmpty { id.toString() })
                engine.deletePartFile(downloadId.ifEmpty { id.toString() })
                YtDlpJobs.forget(downloadId.ifEmpty { id.toString() })
            }
            is DownloadResult.Paused -> {
                // Keep the row + every .part file. Resume reuses both.
                dao.updateProgress(id, DownloadStatus.DOWNLOADING, lastPct.coerceIn(0, 99))
            }
        }

        Pair(id, result)
    }

    /**
     * Direct download with one automatic fresh-URL retry. CDN stream URLs
     * (YouTube InnerTube, Instagram, Threads) expire quickly, so a 403/401
     * used to force the user through 3-4 manual analyze + download rounds.
     * Now the first 403/401 re-extracts once and retries with the fresh
     * URL automatically; only a second failure is shown to the user.
     */
    private suspend fun downloadDirectWithRefresh(
        info: VideoInfo,
        format: VideoFormat,
        url: String,
        downloadId: String,
        dbId: Long,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val progressSink: suspend (DownloadProgress) -> Unit = { progress ->
            dao.updateProgress(dbId, DownloadStatus.DOWNLOADING, progress.percent.coerceIn(0, 99))
            onProgress(progress)
        }
        val first = engine.download(info, format, url, downloadId, progressSink)
        if (first is DownloadResult.Error &&
            (first.message.contains("HTTP 403") || first.message.contains("HTTP 401")) &&
            info.platform in listOf(Platform.YOUTUBE, Platform.INSTAGRAM, Platform.THREADS)
        ) {
            val fresh = extractor.extract(info.url)
            val freshFormats = (fresh as? VideoExtractor.Result.Success)?.info?.formats.orEmpty()
            val freshUrl = freshFormats
                .firstOrNull { it.quality == format.quality && it.downloadUrl.isNotBlank() }?.downloadUrl
                ?: freshFormats.firstOrNull { it.downloadUrl.isNotBlank() }?.downloadUrl
            if (!freshUrl.isNullOrBlank() && freshUrl != url) {
                Log.d("VidFlow/Refresh", "Retrying expired URL with a fresh one")
                return@withContext engine.download(info, format, freshUrl, downloadId, progressSink)
            }
        }
        first
    }

    /** Pinterest CDN tokens expire quickly — fetch a fresh MP4 URL right before download. */
    private fun refreshPinterestUrl(pageUrl: String, existing: String): String {
        return try {
            val fresh = com.vidflow.app.data.extractor.PinterestExtractor(httpClient).extract(pageUrl)
            (fresh as? VideoExtractor.Result.Success)?.info?.formats
                ?.firstOrNull { it.downloadUrl.isNotBlank() }
                ?.downloadUrl
                ?: existing
        } catch (_: Exception) {
            existing
        }
    }

    // ── yt-dlp plumbing ───────────────────────────────────────────────────────

    /** Outcome of one blocking yt-dlp execute() call. */
    private sealed class YtDlpOutcome {
        object Finished : YtDlpOutcome()
        object Paused : YtDlpOutcome()
        object Cancelled : YtDlpOutcome()
        data class Failed(val message: String) : YtDlpOutcome()
    }

    /**
     * Runs one yt-dlp command with a stable process id. The process id is
     * what pause/cancel kill via YtDlpJobs; the same id is reused on resume.
     *
     * @param percentMap remaps yt-dlp's 0..100 progress (used by the
     *   split-download path: video 0..45, audio 45..90, merge 90..99).
     */
    private fun executeYtDlp(
        request: YoutubeDLRequest,
        processId: String,
        downloadId: String,
        dbId: Long,
        percentMap: (Int) -> Int = { it },
        /**
         * Phase-aware mapping for yt-dlp's own merged downloads (single
         * path). The split path passes its own percentMap and sets this
         * false, because it already weights video/audio/merge itself.
         */
        phaseAware: Boolean = true,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): YtDlpOutcome {
        YtDlpJobs.register(downloadId, processId)
        var lastPct = -1
        var totalBytesParsed = -1L
        var merging = false
        // Single-path multi-file downloads: yt-dlp reports EACH file's
        // progress 0..100, so the bar used to jump back to 0 when the
        // second file started. Track file phases and map them onto one
        // monotonic bar: file 1 -> 0..50, file 2 -> 50..90, merge -> 90..99.
        // A phase change is a large backward jump after real progress,
        // never a small wobble.
        var prevRaw = -1
        var phase = 0
        try {
            YoutubeDL.getInstance().execute(request, processId) { progress, _, line ->
                if (line != null && (line.contains("[Merger]") || line.contains("Merging formats"))) {
                    merging = true
                }
                val rawPct = progress.toInt().coerceIn(0, 100)
                if (phaseAware && !merging && prevRaw > 50 && rawPct < prevRaw - 30 && phase == 0) {
                    phase = 1
                }
                if (phaseAware && !merging) prevRaw = rawPct
                val pct = when {
                    merging -> maxOf(lastPct, 90)
                    phaseAware && phase == 1 -> 50 + (rawPct * 40) / 100
                    else -> percentMap(rawPct)
                }
                // Monotonic clamp: the displayed bar never goes backwards.
                val mapped = pct.coerceIn(0, 99).coerceAtLeast(lastPct.coerceAtLeast(0))
                if (mapped != lastPct || merging) {
                    lastPct = mapped
                    val parsedTotal = parseBytesFromLog(line)
                    if (parsedTotal > 0) totalBytesParsed = parsedTotal
                    // During merge there is no download speed: report none
                    // (the UI shows "Merging..." instead of 0 KB/s).
                    val speed = if (merging) 0L else parseSpeedFromLog(line)
                    val downloaded = if (totalBytesParsed > 0 && !merging) {
                        (totalBytesParsed * mapped) / 100
                    } else if (totalBytesParsed > 0) {
                        totalBytesParsed
                    } else {
                        0L
                    }
                    runBlocking {
                        dao.updateProgress(dbId, DownloadStatus.DOWNLOADING, mapped)
                        onProgress(
                            DownloadProgress(
                                downloaded, totalBytesParsed, speed, mapped,
                                isMerging = merging,
                            ),
                        )
                    }
                }
            }
        } catch (e: Exception) {
            // A killed process surfaces here as an exception. Pause/cancel
            // win over "failed": they are user actions, not errors.
            if (YtDlpJobs.isCancelled(downloadId)) return YtDlpOutcome.Cancelled
            if (YtDlpJobs.isPaused(downloadId)) return YtDlpOutcome.Paused
            Log.w("VidFlow/YtDlp", "execute failed: ${e.message}")
            return YtDlpOutcome.Failed("yt-dlp failed: ${e.message?.take(120)}")
        } finally {
            YtDlpJobs.unregister(downloadId, processId)
        }
        if (YtDlpJobs.isCancelled(downloadId)) return YtDlpOutcome.Cancelled
        if (YtDlpJobs.isPaused(downloadId)) return YtDlpOutcome.Paused
        return YtDlpOutcome.Finished
    }

    private fun YoutubeDLRequest.withCommonOptions(
        info: VideoInfo,
        downloadId: String,
    ): YoutubeDLRequest {
        addOption("--no-playlist")
        addOption("--no-warnings")
        addOption("--no-check-certificates")
        addOption("--socket-timeout", "30")
        addOption("--extractor-retries", "3")
        // Resume: never delete .part files on pause; --continue reuses them.
        addOption("--continue")
        addOption("--fragment-retries", "10")
        // Parallel fragments: the single biggest download-speed win for
        // fragmented (DASH/HLS) streams, the way fast downloader apps work.
        addOption("--concurrent-fragments", "4")
        SessionCookies.writeCookiesTxt(appContext, info.platform)?.let { cookies ->
            addOption("--cookies", cookies.absolutePath)
        }
        return this
    }

    private fun findProduced(template: String, extensions: List<String>): File? {
        val dir = File(template).parentFile ?: return null
        val base = File(template).name
        return extensions.map { ext -> File(dir, "$base.$ext") }
            .firstOrNull { it.exists() && it.length() > 0 }
            ?: dir.listFiles { f -> f.name.startsWith(base) && !f.name.contains(".part") }
                ?.firstOrNull { it.length() > 0 }
    }

    /**
     * Downloads MP3 audio using yt-dlp with --extract-audio --audio-format mp3.
     * This is the correct way to get an actual .mp3 file from any platform.
     */
    private suspend fun downloadWithYtDlpAudio(
        info: VideoInfo,
        dbId: Long,
        downloadId: String,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val safeTitle = info.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifEmpty { "audio" }
        val key = downloadId.ifEmpty { dbId.toString() }
        val outputTemplate = YtDlpJobs.templateFor(appContext, key)
        Log.d("VidFlow/YtDlp-MP3", "Start MP3 download: ${info.url}")

        val request = YoutubeDLRequest(info.url).apply {
            addOption("-f", "bestaudio/best")
            addOption("-o", "${outputTemplate}.%(ext)s")
            addOption("--extract-audio")
            addOption("--audio-format", "mp3")
            addOption("--audio-quality", "0") // best quality
            withCommonOptions(info, key)
        }
        when (val outcome = executeYtDlp(request, "ytdlp_${key}_audio", key, dbId, onProgress = onProgress)) {
            is YtDlpOutcome.Paused -> return@withContext DownloadResult.Paused
            is YtDlpOutcome.Cancelled -> return@withContext DownloadResult.Cancelled
            is YtDlpOutcome.Failed -> return@withContext DownloadResult.Error("MP3 download failed: ${outcome.message}")
            is YtDlpOutcome.Finished -> Unit
        }

        val outputFile = findProduced(outputTemplate, listOf("mp3", "m4a", "opus", "webm"))
            ?: return@withContext DownloadResult.Error(
                "MP3 extraction failed: output file not found. Ensure FFmpeg is initialized.",
            )
        val fileSize = outputFile.length()
        val finalPath = saveToDownloads(outputFile, "$safeTitle.mp3", "audio/mpeg")
        outputFile.delete()
        YtDlpJobs.forget(key)

        onProgress(DownloadProgress(fileSize, fileSize, 0L, 100))
        Log.d("VidFlow/YtDlp-MP3", "Done: $finalPath (${fileSize / 1024}KB)")
        DownloadResult.Success(finalPath, fileSize)
    }

    /**
     * Downloads using yt-dlp. For combined video+audio selectors (DASH),
     * video and audio are downloaded as two separate files and then merged
     * by the on-device MediaMuxer step with real merge progress; if that
     * merge cannot handle the codecs, yt-dlp's own merge is the fallback.
     * Single-format selectors go straight through yt-dlp.
     */
    private suspend fun downloadWithYtDlp(
        info: VideoInfo,
        formatSelector: String,
        format: VideoFormat,
        dbId: Long,
        downloadId: String,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val safeTitle = info.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifEmpty { "video" }
        val key = downloadId.ifEmpty { dbId.toString() }
        val outputTemplate = YtDlpJobs.templateFor(appContext, key)
        Log.d("VidFlow/YtDlp", "Start: $formatSelector | ${info.url}")

        // Split path only for combined selectors (a '+' joins video+audio).
        if (formatSelector.contains("+")) {
            val split = downloadSplitAndMux(info, format, key, outputTemplate, dbId, onProgress)
            if (split != null) return@withContext split
            // split == null means muxing was impossible for these codecs:
            // fall through to yt-dlp's own merge below (parts are reused).
            Log.d("VidFlow/YtDlp", "Split mux unavailable, falling back to yt-dlp merge")
        }

        val request = YoutubeDLRequest(info.url).apply {
            addOption("-f", formatSelector)
            addOption("-o", "${outputTemplate}.%(ext)s")
            addOption("--merge-output-format", "mp4")
            withCommonOptions(info, key)
        }
        when (val outcome = executeYtDlp(request, "ytdlp_$key", key, dbId, onProgress = onProgress)) {
            is YtDlpOutcome.Paused -> return@withContext DownloadResult.Paused
            is YtDlpOutcome.Cancelled -> return@withContext DownloadResult.Cancelled
            is YtDlpOutcome.Failed -> return@withContext DownloadResult.Error(outcome.message)
            is YtDlpOutcome.Finished -> Unit
        }

        val outputFile = findProduced(outputTemplate, listOf("mp4", "mkv", "webm", "m4a", "mp3"))
            ?: return@withContext DownloadResult.Error(
                "Merge failed: output file not found. Ensure FFmpeg is initialized.",
            )
        val fileSize = outputFile.length()
        val fileName = "${safeTitle}.${outputFile.extension.ifEmpty { "mp4" }}"
        val mimeType = when (outputFile.extension.lowercase()) {
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "opus" -> "audio/opus"
            else -> "video/mp4"
        }
        val finalPath = saveToDownloads(outputFile, fileName, mimeType)
        outputFile.delete()
        YtDlpJobs.forget(key)

        onProgress(DownloadProgress(fileSize, fileSize, 0L, 100))
        Log.d("VidFlow/YtDlp", "Done: $finalPath (${fileSize / 1024}KB)")
        DownloadResult.Success(finalPath, fileSize)
    }

    /**
     * Split DASH download + separate merge step.
     * Returns null ONLY when the merge itself is impossible (codec/container
     * the muxer cannot handle) — the caller then falls back to yt-dlp merge.
     * Pause/Cancel propagate as results, never as null.
     */
    private suspend fun downloadSplitAndMux(
        info: VideoInfo,
        format: VideoFormat,
        key: String,
        outputTemplate: String,
        dbId: Long,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult? = withContext(Dispatchers.IO) {
        val height = Regex("""(\d{3,4})p""").find(format.quality)?.groupValues?.get(1)?.toIntOrNull()
        val videoSelector = if (height != null) "bestvideo[height<=$height]/bestvideo" else "bestvideo"
        val audioSelector = "bestaudio/best"
        val videoTemplate = "${outputTemplate}_v"
        val audioTemplate = "${outputTemplate}_a"

        val videoRequest = YoutubeDLRequest(info.url).apply {
            addOption("-f", videoSelector)
            addOption("-o", "${videoTemplate}.%(ext)s")
            withCommonOptions(info, key)
        }
        when (val outcome = executeYtDlp(
            videoRequest, "ytdlp_${key}_v", key, dbId,
            percentMap = { (it * 45) / 100 }, phaseAware = false, onProgress = onProgress,
        )) {
            is YtDlpOutcome.Paused -> return@withContext DownloadResult.Paused
            is YtDlpOutcome.Cancelled -> return@withContext DownloadResult.Cancelled
            is YtDlpOutcome.Failed -> {
                // A single-format failure here must not kill the whole job:
                // the classic merged path below gets its own chance.
                Log.w("VidFlow/YtDlp", "Split video fetch failed, fallback: ${outcome.message}")
                return@withContext null
            }
            is YtDlpOutcome.Finished -> Unit
        }
        val videoFile = findProduced(videoTemplate, listOf("mp4", "mkv", "webm"))
            ?: return@withContext null

        val audioRequest = YoutubeDLRequest(info.url).apply {
            addOption("-f", audioSelector)
            addOption("-o", "${audioTemplate}.%(ext)s")
            withCommonOptions(info, key)
        }
        when (val outcome = executeYtDlp(
            audioRequest, "ytdlp_${key}_a", key, dbId,
            percentMap = { 45 + (it * 45) / 100 }, phaseAware = false, onProgress = onProgress,
        )) {
            is YtDlpOutcome.Paused -> return@withContext DownloadResult.Paused
            is YtDlpOutcome.Cancelled -> return@withContext DownloadResult.Cancelled
            is YtDlpOutcome.Failed -> {
                Log.w("VidFlow/YtDlp", "Split audio fetch failed, fallback: ${outcome.message}")
                return@withContext null
            }
            is YtDlpOutcome.Finished -> Unit
        }
        val audioFile = findProduced(audioTemplate, listOf("m4a", "mp4", "webm", "opus"))
            ?: return@withContext null

        // Separate merge step with real progress (brief 3.2, via MediaMuxer).
        val merged = File("${outputTemplate}.merged.mp4")
        var lastMuxPct = -1
        val muxed = MediaMuxerMerger.merge(videoFile, audioFile, merged) { muxPct ->
            val overall = 90 + (muxPct * 9) / 100
            if (overall != lastMuxPct) {
                lastMuxPct = overall
                runBlocking {
                    dao.updateProgress(dbId, DownloadStatus.DOWNLOADING, overall)
                    onProgress(DownloadProgress(0L, -1L, 0L, overall, isMerging = true))
                }
            }
        } ?: return@withContext null

        val safeTitle = info.title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifEmpty { "video" }
        val fileSize = muxed.length()
        val finalPath = saveToDownloads(muxed, "$safeTitle.mp4", "video/mp4")
        runCatching { muxed.delete() }
        runCatching { videoFile.delete() }
        runCatching { audioFile.delete() }
        YtDlpJobs.forget(key)
        onProgress(DownloadProgress(fileSize, fileSize, 0L, 100))
        DownloadResult.Success(finalPath, fileSize)
    }

    /** Saves a file to Music/VidFlow/ (for audio) or Downloads/VidFlow/ (for video) via scoped storage or legacy path. */
    private fun saveToDownloads(src: File, fileName: String, mimeType: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isAudio = mimeType.startsWith("audio/")
            val targetDir = if (isAudio) "${Environment.DIRECTORY_MUSIC}/VidFlow" else "${Environment.DIRECTORY_DOWNLOADS}/VidFlow"
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, targetDir)
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collectionUri = if (isAudio) {
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            } else {
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            }

            try {
                val uri = appContext.contentResolver.insert(collectionUri, cv)
                    ?: run {
                        // Fallback to MediaStore.Downloads for audio if Audio.Media returned null
                        val fallbackCv = ContentValues().apply {
                            put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                            put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidFlow")
                            put(MediaStore.MediaColumns.IS_PENDING, 1)
                        }
                        appContext.contentResolver.insert(
                            MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), fallbackCv
                        ) ?: error("MediaStore insert returned null for $fileName")
                    }

                appContext.contentResolver.openOutputStream(uri)?.use { out ->
                    src.inputStream().use { it.copyTo(out) }
                }
                appContext.contentResolver.update(
                    uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null,
                )
                uri.toString()
            } catch (e: Exception) {
                // Secondary fallback: save to public Downloads/VidFlow or app files dir
                Log.e("VidFlow/Storage", "Scoped storage save failed: ${e.message}, falling back to legacy", e)
                @Suppress("DEPRECATION")
                val fallbackFolder = File(
                    Environment.getExternalStoragePublicDirectory(
                        if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS
                    ), "VidFlow"
                )
                fallbackFolder.mkdirs()
                val dest = File(fallbackFolder, fileName)
                src.copyTo(dest, overwrite = true)
                dest.absolutePath
            }
        } else {
            val isAudio = mimeType.startsWith("audio/")
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(
                    if (isAudio) Environment.DIRECTORY_MUSIC else Environment.DIRECTORY_DOWNLOADS
                ), "VidFlow",
            )
            dir.mkdirs()
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    // ── History ───────────────────────────────────────────────────────────────

    fun getAllDownloads(): kotlinx.coroutines.flow.Flow<List<DownloadRecord>> = dao.getAllRecords()
    fun getActiveDownloads(): kotlinx.coroutines.flow.Flow<List<DownloadRecord>> = dao.getActiveDownloads()
    fun getCompletedDownloads(): kotlinx.coroutines.flow.Flow<List<DownloadRecord>> = dao.getCompletedDownloads()
    suspend fun deleteRecord(id: Long) = dao.delete(id)
    suspend fun clearHistory() = dao.clearHistory()

    private fun parseBytesFromLog(line: String?): Long {
        if (line == null) return -1L
        val match = Regex("""of\s+~?\s*([\d.]+)\s*([KMGTP]?i?B)""", RegexOption.IGNORE_CASE).find(line)
            ?: return -1L
        val num = match.groupValues[1].toDoubleOrNull() ?: return -1L
        val unit = match.groupValues[2].uppercase()
        val mult = when {
            unit.startsWith("G") -> 1024L * 1024 * 1024
            unit.startsWith("M") -> 1024L * 1024
            unit.startsWith("K") -> 1024L
            else -> 1L
        }
        return (num * mult).toLong()
    }

    private fun parseSpeedFromLog(line: String?): Long {
        if (line == null) return 0L
        val match = Regex("""at\s+([\d.]+)\s*([KMGTP]?i?B)/s""", RegexOption.IGNORE_CASE).find(line)
            ?: return 0L
        val num = match.groupValues[1].toDoubleOrNull() ?: return 0L
        val unit = match.groupValues[2].uppercase()
        val mult = when {
            unit.startsWith("G") -> 1024L * 1024 * 1024
            unit.startsWith("M") -> 1024L * 1024
            unit.startsWith("K") -> 1024L
            else -> 1L
        }
        return (num * mult).toLong()
    }
}
