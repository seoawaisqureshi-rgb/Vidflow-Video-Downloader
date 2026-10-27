package com.vidflow.app.data

import android.app.DownloadManager
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import java.io.File

/**
 * Handles opening the downloads/media folder according to the specification:
 * - If Samsung My Files (com.sec.android.app.myfiles) is installed, open in Samsung My Files.
 * - Otherwise, prompt with the system app chooser and remember the user's choice.
 * - Allows resetting the remembered file manager choice from Settings.
 */
object FileManagerHelper {

    private const val PREFS_NAME = "vidflow_file_manager_prefs"
    private const val KEY_REMEMBERED_PACKAGE = "preferred_file_app_package"
    const val SAMSUNG_MY_FILES_PACKAGE = "com.sec.android.app.myfiles"

    private fun getPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    fun isSamsungMyFilesInstalled(context: Context): Boolean {
        return isPackageInstalled(context, SAMSUNG_MY_FILES_PACKAGE)
    }

    fun getRememberedPackage(context: Context): String? {
        return getPrefs(context).getString(KEY_REMEMBERED_PACKAGE, null)
    }

    fun setRememberedPackage(context: Context, packageName: String?) {
        getPrefs(context).edit().apply {
            if (packageName == null) remove(KEY_REMEMBERED_PACKAGE)
            else putString(KEY_REMEMBERED_PACKAGE, packageName)
            apply()
        }
    }

    fun resetRememberedChoice(context: Context) {
        setRememberedPackage(context, null)
    }

    private fun isPackageInstalled(context: Context, packageName: String): Boolean {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.packageManager.getPackageInfo(
                    packageName,
                    android.content.pm.PackageManager.PackageInfoFlags.of(0)
                )
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo(packageName, 0)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Opens the folder where files are stored.
     * Follows the exact rule:
     * 1. Samsung My Files if installed.
     * 2. Remembered chosen file app if set and still installed.
     * 3. System app chooser, remembering choice.
     */
    fun openFolder(context: Context, targetPath: String? = null) {
        // 1. Samsung My Files check
        if (isSamsungMyFilesInstalled(context)) {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(SAMSUNG_MY_FILES_PACKAGE)
            if (launchIntent != null) {
                launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(launchIntent)
                    return
                } catch (_: Exception) {}
            }
        }

        // 2. Check remembered choice
        val rememberedPkg = getRememberedPackage(context)
        if (!rememberedPkg.isNullOrBlank() && isPackageInstalled(context, rememberedPkg)) {
            val rememberedIntent = context.packageManager.getLaunchIntentForPackage(rememberedPkg)
            if (rememberedIntent != null) {
                rememberedIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                try {
                    context.startActivity(rememberedIntent)
                    return
                } catch (_: Exception) {}
            }
        }

        // 3. System Chooser fallback:
        // Try to open DocumentsUI / Downloads directory with chooser
        val downloadUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2FVidFlow")
        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(downloadUri, "vnd.android.document/directory")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        val resolveInfos = context.packageManager.queryIntentActivities(viewIntent, 0)
        if (resolveInfos.isNotEmpty()) {
            val chooser = Intent.createChooser(viewIntent, "Select File Manager").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(chooser)
                return
            } catch (_: Exception) {}
        }

        // Alternate chooser: DownloadManager or MediaStore Downloads
        try {
            val dmIntent = Intent(DownloadManager.ACTION_VIEW_DOWNLOADS).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val chooser = Intent.createChooser(dmIntent, "Select File Manager").apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(chooser)
        } catch (e: Exception) {
            Toast.makeText(context, "Could not find a file manager app", Toast.LENGTH_SHORT).show()
        }
    }
}
