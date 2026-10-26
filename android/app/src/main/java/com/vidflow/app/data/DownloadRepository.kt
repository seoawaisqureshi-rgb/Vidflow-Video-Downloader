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
import com.vidflow.app.data.extractor.VideoExtractor
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
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
    private val engine    = DownloadEngine(appContext, httpClient)
    private val dao: DownloadDao = AppDatabase.getInstance(appContext).downloadDao()

    // ── Extraction ────────────────────────────────────────────────────────────

    suspend fun fetchVideoInfo(url: String): VideoExtractor.Result {
        return withContext(Dispatchers.IO) { extractor.extract(url) }
    }

    // ── Download ──────────────────────────────────────────────────────────────

    suspend fun startDownload(
        info: VideoInfo,
        format: VideoFormat,
        downloadUrl: String,
        downloadId: String = "",
        onProgress: suspend (DownloadProgress) -> Unit,
    ): Pair<Long, DownloadResult> = withContext(Dispatchers.IO) {
        val record = DownloadRecord(
            url = info.url, title = info.title, thumbnail = info.thumbnail,
            platform = info.platform, quality = format.quality, ext = format.ext,
            filePath = null, fileSize = null, duration = info.duration,
            status = DownloadStatus.DOWNLOADING, progress = 0,
        )
        val id = dao.insert(record)

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
                downloadWithYtDlpAudio(info.url, info.title, id, onProgress)
            }
            // Explicitly marked as needing yt-dlp (DASH merge, HD, etc.)
            format.needsYtDlp -> {
                val selector = format.ytDlpFormatSelector.ifEmpty {
                    if (format.hasVideo && !format.hasAudio) "${format.formatId}+bestaudio/bestvideo+bestaudio"
                    else if (!format.hasVideo && format.hasAudio) format.formatId
                    else format.formatId.ifEmpty { "bestvideo+bestaudio/best" }
                }
                downloadWithYtDlp(info.url, info.title, selector, format.ext, id, onProgress)
            }
            // YouTube with blank URL (legacy path)
            info.platform == Platform.YOUTUBE && downloadUrl.isBlank() -> {
                val selector = format.ytDlpFormatSelector.ifEmpty {
                    if (format.hasVideo && !format.hasAudio)
                        "${format.formatId}+bestaudio[ext=m4a]/${format.formatId}+bestaudio/bestvideo+bestaudio"
                    else format.formatId.ifEmpty { "bestvideo+bestaudio/best" }
                }
                downloadWithYtDlp(info.url, info.title, selector, format.ext, id, onProgress)
            }
            // Direct HTTP download (fast path via OkHttp)
            else -> engine.download(info, format, resolvedUrl, downloadId.ifEmpty { id.toString() }) { progress ->
                dao.updateProgress(id, DownloadStatus.DOWNLOADING, progress.percent.coerceIn(0, 99))
                onProgress(progress)
            }
        }

        when (result) {
            is DownloadResult.Success   -> dao.markCompleted(id, DownloadStatus.COMPLETED,
                result.filePath, result.fileSize, System.currentTimeMillis())
            is DownloadResult.Error     -> dao.updateError(id, DownloadStatus.FAILED, result.message)
            is DownloadResult.Cancelled -> dao.updateError(id, DownloadStatus.CANCELLED, "Cancelled by user")
        }

        Pair(id, result)
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

    /**
     * Downloads MP3 audio using yt-dlp with --extract-audio --audio-format mp3.
     * This is the correct way to get an actual .mp3 file from any platform.
     */
    private suspend fun downloadWithYtDlpAudio(
        videoUrl: String,
        title: String,
        dbId: Long,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifEmpty { "audio" }
        val tempId    = System.currentTimeMillis()
        val tempDir   = appContext.cacheDir
        val outputTemplate = "${tempDir.absolutePath}/vf_${tempId}"

        Log.d("VidFlow/YtDlp-MP3", "Start MP3 download: $videoUrl")

        try {
            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-f", "bestaudio/best")
                addOption("-o", "${outputTemplate}.%(ext)s")
                addOption("--extract-audio")
                addOption("--audio-format", "mp3")
                addOption("--audio-quality", "0")  // best quality
                addOption("--no-playlist")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--socket-timeout", "30")
            }

            var lastPct = -1
            var totalBytesParsed = -1L
            YoutubeDL.getInstance().execute(request, videoUrl) { progress, _, line ->
                val pct = progress.toInt().coerceIn(0, 99)
                if (pct != lastPct) {
                    lastPct = pct
                    val parsedTotal = parseBytesFromLog(line)
                    if (parsedTotal > 0) totalBytesParsed = parsedTotal
                    val speed = parseSpeedFromLog(line)
                    val downloaded = if (totalBytesParsed > 0) (totalBytesParsed * pct) / 100 else 0L
                    runBlocking {
                        dao.updateProgress(dbId, DownloadStatus.DOWNLOADING, pct)
                        onProgress(DownloadProgress(downloaded, totalBytesParsed, speed, pct))
                    }
                }
            }

            // Find the mp3 output file
            val outputFile = listOf("mp3", "m4a", "opus", "webm")
                .map { e -> File("${outputTemplate}.$e") }
                .firstOrNull { it.exists() }
                ?: tempDir.listFiles { f -> f.name.startsWith("vf_${tempId}") }?.firstOrNull()

            if (outputFile == null || !outputFile.exists()) {
                return@withContext DownloadResult.Error(
                    "MP3 extraction failed: output file not found. Ensure FFmpeg is initialized."
                )
            }

            val fileSize = outputFile.length()
            val fileName = "${safeTitle}.mp3"
            val finalPath = saveToDownloads(outputFile, fileName, "audio/mpeg")
            outputFile.delete()

            onProgress(DownloadProgress(fileSize, fileSize, 0L, 100))
            Log.d("VidFlow/YtDlp-MP3", "Done: $finalPath (${fileSize / 1024}KB)")
            DownloadResult.Success(finalPath, fileSize)

        } catch (e: Exception) {
            Log.e("VidFlow/YtDlp-MP3", "Failed: ${e.message}", e)
            DownloadResult.Error("MP3 download failed: ${e.message?.take(100)}")
        }
    }

    /**
     * Downloads using yt-dlp execute() — handles:
     * - YouTube DASH 1080p/720p (video+audio merge via FFmpeg)
     * - Facebook DASH (audio+video separate streams)
     * - Threads, Instagram, any platform with separate streams
     *
     * Progress reports 0→99% from yt-dlp callback (FFmpeg merge has no progress),
     * then 100% when file is ready.
     */
    private suspend fun downloadWithYtDlp(
        videoUrl: String,
        title: String,
        formatSelector: String,
        ext: String,
        dbId: Long,
        onProgress: suspend (DownloadProgress) -> Unit,
    ): DownloadResult = withContext(Dispatchers.IO) {
        val safeTitle = title.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim().take(60).ifEmpty { "video" }
        val tempId    = System.currentTimeMillis()
        val tempDir   = appContext.cacheDir
        val outputTemplate = "${tempDir.absolutePath}/vf_${tempId}"

        Log.d("VidFlow/YtDlp", "Start: $formatSelector | $videoUrl")

        try {
            val request = YoutubeDLRequest(videoUrl).apply {
                addOption("-f",  formatSelector)
                addOption("-o",  "${outputTemplate}.%(ext)s")
                addOption("--no-playlist")
                addOption("--merge-output-format", "mp4")
                addOption("--no-warnings")
                addOption("--no-check-certificates")
                addOption("--socket-timeout", "30")
                addOption("--extractor-retries", "3")
            }

            var lastPct = -1
            var totalBytesParsed = -1L
            YoutubeDL.getInstance().execute(request, videoUrl) { progress, _, line ->
                val pct = progress.toInt().coerceIn(0, 99)
                if (pct != lastPct || line != null) {
                    lastPct = pct
                    val parsedTotal = parseBytesFromLog(line)
                    if (parsedTotal > 0) totalBytesParsed = parsedTotal
                    val speed = parseSpeedFromLog(line)
                    val downloaded = if (totalBytesParsed > 0) (totalBytesParsed * pct) / 100 else 0L
                    runBlocking {
                        dao.updateProgress(dbId, DownloadStatus.DOWNLOADING, pct)
                        onProgress(DownloadProgress(downloaded, totalBytesParsed, speed, pct))
                    }
                }
            }

            // Find the merged output file
            val outputFile = listOf("mp4", "mkv", "webm", "m4a", "mp3")
                .map { e -> File("${outputTemplate}.$e") }
                .firstOrNull { it.exists() }
                ?: tempDir.listFiles { f -> f.name.startsWith("vf_${tempId}") }?.firstOrNull()

            if (outputFile == null || !outputFile.exists()) {
                return@withContext DownloadResult.Error(
                    "Merge failed: output file not found. Ensure FFmpeg is initialized."
                )
            }

            val fileSize = outputFile.length()
            val fileName = "${safeTitle}.${outputFile.extension.ifEmpty { "mp4" }}"
            val mimeType = when (outputFile.extension.lowercase()) {
                "mp3"  -> "audio/mpeg"
                "m4a"  -> "audio/mp4"
                "opus" -> "audio/opus"
                else   -> "video/mp4"
            }

            val finalPath = saveToDownloads(outputFile, fileName, mimeType)
            outputFile.delete()

            onProgress(DownloadProgress(fileSize, fileSize, 0L, 100))
            Log.d("VidFlow/YtDlp", "Done: $finalPath (${fileSize / 1024}KB)")
            DownloadResult.Success(finalPath, fileSize)

        } catch (e: Exception) {
            Log.e("VidFlow/YtDlp", "Failed: ${e.message}", e)
            DownloadResult.Error("yt-dlp download failed: ${e.message?.take(100)}")
        }
    }

    /** Saves a file to Downloads/VidFlow/ via scoped storage (Android 10+) or legacy path. */
    private fun saveToDownloads(src: File, fileName: String, mimeType: String): String {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val isAudio = mimeType.startsWith("audio/")
            val cv = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidFlow")
                put(MediaStore.MediaColumns.IS_PENDING, 1)
            }
            val collectionUri = if (isAudio)
                MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            else
                MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)

            val uri = appContext.contentResolver.insert(collectionUri, cv)
                ?: error("MediaStore insert returned null for $fileName")

            appContext.contentResolver.openOutputStream(uri)?.use { out ->
                src.inputStream().use { it.copyTo(out) }
            }
            appContext.contentResolver.update(
                uri, ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }, null, null
            )
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(
                Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidFlow"
            )
            dir.mkdirs()
            val dest = File(dir, fileName)
            src.copyTo(dest, overwrite = true)
            dest.absolutePath
        }
    }

    // ── History ───────────────────────────────────────────────────────────────

    fun getAllDownloads(): Flow<List<DownloadRecord>>     = dao.getAllRecords()
    fun getActiveDownloads(): Flow<List<DownloadRecord>> = dao.getActiveDownloads()
    fun getCompletedDownloads(): Flow<List<DownloadRecord>> = dao.getCompletedDownloads()
    suspend fun deleteRecord(id: Long) = dao.delete(id)
    suspend fun clearHistory()         = dao.clearHistory()

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