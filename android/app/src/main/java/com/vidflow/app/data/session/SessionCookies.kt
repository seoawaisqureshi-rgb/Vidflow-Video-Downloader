package com.vidflow.app.data.session

import android.content.Context
import android.util.Log
import android.webkit.CookieManager
import com.vidflow.app.data.model.Platform
import java.io.File

/**
 * WebView session cookies, shared by the login screen, the in-app browser
 * and every extractor / downloader.
 *
 * Rules (brief section 2.3):
 * - The app NEVER sees or stores a password. Login happens inside a WebView.
 * - Cookies stay on the device, in app-private storage. They are never uploaded.
 * - yt-dlp gets them only as a local Netscape-format cookies.txt file.
 * - Logout deletes the WebView cookies and the cookies.txt file.
 */
object SessionCookies {

    private const val TAG = "VidFlow/Session"

    /** Platforms the Login screen offers, with the page the user logs in on. */
    enum class LoginPlatform(
        val label: String,
        val loginUrl: String,
        /** Hosts whose cookies count as "logged in" for this platform. */
        val cookieHosts: List<String>,
        val platform: Platform,
    ) {
        INSTAGRAM(
            "Instagram", "https://www.instagram.com/accounts/login/",
            listOf("instagram.com"), Platform.INSTAGRAM,
        ),
        FACEBOOK(
            "Facebook", "https://www.facebook.com/login/",
            listOf("facebook.com"), Platform.FACEBOOK,
        ),
        THREADS(
            "Threads", "https://www.threads.net/login/",
            listOf("threads.net", "instagram.com"), Platform.THREADS,
        ),
    }

    fun platformFor(platform: Platform): LoginPlatform? =
        LoginPlatform.entries.firstOrNull { it.platform == platform }

    /** Raw "name=value; name=value" cookie header for [url], or null. */
    fun getCookieHeader(url: String): String? = try {
        CookieManager.getInstance().getCookie(url)?.takeIf { it.isNotBlank() }
    } catch (e: Exception) {
        Log.d(TAG, "getCookie failed: ${e.message}")
        null
    }

    /** True when the WebView holds at least one session cookie for [platform]. */
    fun hasSession(platform: Platform): Boolean {
        val login = platformFor(platform)
        if (login != null) return login.cookieHosts.any { isLikelySessionCookie(it) }
        val host = platform.domains.firstOrNull() ?: return false
        return getCookieHeader("https://$host/") != null
    }

    /**
     * A host counts as logged in when it has any cookie whose name looks like
     * a session cookie (sessionid, c_user, sb, ds_user_id, etc.). A bare
     * tracking cookie alone is not enough, so login-walled pages do not get a
     * false "logged in" signal.
     */
    private fun isLikelySessionCookie(host: String): Boolean {
        val header = getCookieHeader("https://$host/") ?: return false
        val sessionNames = listOf(
            "sessionid", "c_user", "sb", "ds_user_id", "ig_did",
            "csrftoken", "datr", "m_pixel_ratio",
        )
        // csrftoken/datr alone only prove a visit, so require a stronger one.
        val names = header.split(";").map { it.substringBefore("=").trim().lowercase() }
        return names.any { it in listOf("sessionid", "c_user", "sb", "ds_user_id") } ||
            ("csrftoken" in names && names.size >= 2)
    }

    /**
     * Writes the WebView cookies for [platform] to a Netscape-format file in
     * app-private cache storage and returns it, or null when there is no
     * session. The file is passed to yt-dlp with --cookies. It is deleted on
     * logout (see [clearAll]).
     */
    fun writeCookiesTxt(context: Context, platform: Platform): File? {
        val hosts = platformFor(platform)?.cookieHosts
            ?: platform.domains.takeIf { it.isNotEmpty() }
            ?: return null
        val expiry = (System.currentTimeMillis() / 1000) + 365L * 24 * 60 * 60
        val lines = mutableListOf("# Netscape HTTP Cookie File - VidFlow local session, do not share")
        var wrote = false
        for (host in hosts) {
            val header = getCookieHeader("https://$host/") ?: continue
            for (pair in header.split(";")) {
                val trimmed = pair.trim()
                if (trimmed.isEmpty()) continue
                val name = trimmed.substringBefore("=")
                val value = trimmed.substringAfter("=", "")
                if (name.isBlank()) continue
                // domain, all-machines flag, path, secure, expiry, name, value
                lines.add(".$host\tTRUE\t/\tFALSE\t$expiry\t$name\t$value")
                wrote = true
            }
        }
        if (!wrote) return null
        return try {
            val file = File(context.cacheDir, "vidflow_cookies_${platform.name.lowercase()}.txt")
            file.writeText(lines.joinToString("\n"))
            file
        } catch (e: Exception) {
            Log.w(TAG, "Could not write cookies.txt: ${e.message}")
            null
        }
    }

    /** Deletes every local cookies.txt file (logout path). */
    fun deleteCookiesFiles(context: Context) {
        try {
            context.cacheDir.listFiles { f -> f.name.startsWith("vidflow_cookies_") }
                ?.forEach { runCatching { it.delete() } }
        } catch (_: Exception) {
        }
    }

    /** Logs out of one platform: clears its WebView cookies + cookies.txt. */
    fun logout(context: Context, platform: Platform) {
        try {
            val mgr = CookieManager.getInstance()
            val hosts = platformFor(platform)?.cookieHosts
                ?: platform.domains.takeIf { it.isNotEmpty() }.orEmpty()
            for (host in hosts) {
                val header = mgr.getCookie("https://$host/")
                header?.split(";")?.forEach { pair ->
                    val name = pair.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        mgr.setCookie("https://$host/", "$name=; Expires=Thu, 01 Jan 1970 00:00:00 GMT; Path=/")
                    }
                }
            }
            mgr.flush()
        } catch (e: Exception) {
            Log.w(TAG, "Logout cookie clear failed: ${e.message}")
        }
        deleteCookiesFiles(context)
    }

    /** Logs out of every login platform. */
    fun logoutAll(context: Context) {
        try {
            CookieManager.getInstance().removeAllCookies(null)
            CookieManager.getInstance().flush()
        } catch (_: Exception) {
        }
        deleteCookiesFiles(context)
    }

    fun loginRequiredMessage(platform: Platform): String =
        "Login required for this ${platform.displayName} content.\n\n" +
            "Open the Login tab, sign in to your ${platform.displayName} account " +
            "inside VidFlow, then try this link again. Your password stays in " +
            "the login page only — VidFlow never sees it."
}
