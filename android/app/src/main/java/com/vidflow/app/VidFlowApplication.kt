package com.vidflow.app

import android.app.Application
import android.content.SharedPreferences
import android.webkit.CookieManager
import android.util.Log
import com.vidflow.app.data.DownloadRepository
import com.vidflow.app.notifications.NotificationHelper
import com.yausername.ffmpeg.FFmpeg
import com.yausername.youtubedl_android.YoutubeDL
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class VidFlowApplication : Application() {

    val repository by lazy { DownloadRepository(this) }

    private val prefs: SharedPreferences by lazy {
        getSharedPreferences("vidflow_prefs", MODE_PRIVATE)
    }

    override fun onCreate() {
        super.onCreate()
        installCrashRecorder(this)
        NotificationHelper.createChannels(this)

        // WebView sessions (Login tab + Browser tab) share one cookie store.
        // Accepting cookies is what makes "log in once, download anywhere" work.
        try {
            CookieManager.getInstance().setAcceptCookie(true)
        } catch (_: Exception) {
        }

        // Initialize yt-dlp + FFmpeg in background.
        // FFmpeg is needed to merge DASH video+audio streams (YouTube 1080p/720p, Facebook, etc.)
        CoroutineScope(Dispatchers.IO).launch {
            try {
                YoutubeDL.getInstance().init(this@VidFlowApplication)
                Log.d("VidFlow", "yt-dlp initialized")

                // Update yt-dlp once per day to ensure latest extractor support
                // (fixes Instagram, Threads, Facebook DASH on newer content)
                val lastUpdate = prefs.getLong("ytdlp_last_update", 0L)
                val oneDayMs   = 24 * 60 * 60 * 1000L
                if (System.currentTimeMillis() - lastUpdate > oneDayMs) {
                    try {
                        val status = YoutubeDL.getInstance().updateYoutubeDL(
                            this@VidFlowApplication,
                            YoutubeDL.UpdateChannel._STABLE,
                        )
                        Log.d("VidFlow", "yt-dlp update: $status")
                        prefs.edit().putLong("ytdlp_last_update", System.currentTimeMillis()).apply()
                    } catch (e: Throwable) {
                        Log.w("VidFlow", "yt-dlp update failed (non-critical): ${e.message}")
                    }
                }
            } catch (e: Throwable) {
                Log.e("VidFlow", "yt-dlp init failed", e)
            }

            try {
                FFmpeg.getInstance().init(this@VidFlowApplication)
                Log.d("VidFlow", "FFmpeg initialized")
            } catch (e: Throwable) {
                Log.e("VidFlow", "FFmpeg init failed", e)
            }
        }
    }
}

internal const val CRASH_REPORT_FILE = "crash.txt"

/**
 * Records uncaught crashes to filesDir/crash.txt so the next launch can show
 * what went wrong, then preserves the default crash behaviour (system dialog
 * + process kill) by chaining to the previously installed handler.
 */
internal fun installCrashRecorder(app: Application) {
    val previous = Thread.getDefaultUncaughtExceptionHandler()
    Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
        try {
            val writer = java.io.StringWriter()
            throwable.printStackTrace(java.io.PrintWriter(writer))
            val report = buildString {
                append("Time: ").append(java.util.Date()).append('\n')
                append("Thread: ").append(thread.name).append('\n')
                append(writer.toString())
            }
            app.filesDir.resolve(CRASH_REPORT_FILE).writeText(report)
        } catch (_: Throwable) {
            // Never let the recorder itself mask the original crash.
        }
        if (previous != null) {
            previous.uncaughtException(thread, throwable)
        } else {
            android.os.Process.killProcess(android.os.Process.myPid())
            kotlin.system.exitProcess(1)
        }
    }
}

internal fun readCrashReport(app: Application): String? {
    return try {
        val file = app.filesDir.resolve(CRASH_REPORT_FILE)
        if (file.exists()) file.readText() else null
    } catch (_: Throwable) {
        null
    }
}

internal fun clearCrashReport(app: Application) {
    try {
        app.filesDir.resolve(CRASH_REPORT_FILE).delete()
    } catch (_: Throwable) {
    }
}
