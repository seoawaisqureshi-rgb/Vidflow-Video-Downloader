package com.vidflow.app.data

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import java.io.File

/**
 * Handles opening Downloads/VidFlow according to Section 3 of Antigravity v4:
 * 1. Samsung attempt: get launch intent for package com.sec.android.app.myfiles,
 *    set action to samsung.myfiles.intent.action.LAUNCH_MY_FILES,
 *    extra samsung.myfiles.intent.extra.START_PATH set to /storage/emulated/0/Download/VidFlow.
 *    The folder MUST exist before this call; create it if missing.
 * 2. System Files fallback: ACTION_VIEW with
 *    DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Download/VidFlow")
 *    and MIME type vnd.android.document/directory.
 * 3. In-app guarantee: if neither external attempt lands on folder, return false to trigger
 *    in-app folder browsing inside My Files screen as primary view.
 * 4. Back behaviour: Launch external folder intent with PLAIN startActivity() and NO
 *    FLAG_ACTIVITY_NEW_TASK added by hand (nor CLEAR_TASK, MULTIPLE_TASK, or NO_HISTORY).
 *    Do NOT use ACTION_OPEN_DOCUMENT_TREE with EXTRA_INITIAL_URI (it's a picker, not a folder viewer).
 */
object FileManagerHelper {

    const val SAMSUNG_MY_FILES_PACKAGE = "com.sec.android.app.myfiles"

    fun getVidFlowFolder(): File {
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val folder = File(downloadsDir, "VidFlow")
        if (!folder.exists()) {
            folder.mkdirs()
        }
        return folder
    }

    fun findActivity(context: Context): Activity? {
        var current = context
        while (current is ContextWrapper) {
            if (current is Activity) return current
            current = current.baseContext
        }
        return null
    }

    private fun startActivitySafely(context: Context, intent: Intent): Boolean {
        return try {
            val activity = findActivity(context)
            if (activity != null) {
                // Launch on existing activity task WITHOUT FLAG_ACTIVITY_NEW_TASK
                // so pressing Back returns directly to VidFlow!
                activity.startActivity(intent)
            } else {
                context.startActivity(intent)
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Executes the folder opening chain in order:
     * Step 1: Samsung My Files START_PATH attempt (without NEW_TASK)
     * Step 2: System Files ACTION_VIEW directory (without NEW_TASK)
     * Step 3: Returns StepResult indicating which step served the folder, or InApp if both external attempts failed.
     */
    enum class OpenFolderResult {
        SAMSUNG_MY_FILES,
        SYSTEM_FILES,
        IN_APP_FALLBACK
    }

    fun openVidFlowFolder(context: Context): OpenFolderResult {
        val folder = getVidFlowFolder()

        // 1. Samsung My Files attempt with both standard Samsung intent action and START_PATH extra variants
        if (isPackageInstalled(context, SAMSUNG_MY_FILES_PACKAGE)) {
            val samsungActions = listOf(
                "com.sec.android.app.myfiles.LAUNCH_MY_FILES",
                "samsung.myfiles.intent.action.LAUNCH_MY_FILES"
            )
            for (actionName in samsungActions) {
                val samsungIntent = Intent(actionName).apply {
                    setPackage(SAMSUNG_MY_FILES_PACKAGE)
                    putExtra("START_PATH", folder.absolutePath)
                    putExtra("samsung.myfiles.intent.extra.START_PATH", folder.absolutePath)
                }
                if (samsungIntent.resolveActivity(context.packageManager) != null) {
                    if (startActivitySafely(context, samsungIntent)) {
                        return OpenFolderResult.SAMSUNG_MY_FILES
                    }
                }
            }

            // Fallback for Samsung: package launch intent with START_PATH
            val rawLaunch = context.packageManager.getLaunchIntentForPackage(SAMSUNG_MY_FILES_PACKAGE)?.apply {
                action = "com.sec.android.app.myfiles.LAUNCH_MY_FILES"
                putExtra("START_PATH", folder.absolutePath)
                putExtra("samsung.myfiles.intent.extra.START_PATH", folder.absolutePath)
                if (findActivity(context) != null) {
                    flags = 0 // Clear auto-attached NEW_TASK flag so Back returns to VidFlow
                }
            }
            if (rawLaunch != null && startActivitySafely(context, rawLaunch)) {
                return OpenFolderResult.SAMSUNG_MY_FILES
            }
        }

        // 2. System Files fallback (AOSP / Pixel / Generic Files app)
        try {
            val docUri = DocumentsContract.buildDocumentUri(
                "com.android.externalstorage.documents",
                "primary:Download/VidFlow"
            )
            val systemIntent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(docUri, "vnd.android.document/directory")
            }
            if (systemIntent.resolveActivity(context.packageManager) != null) {
                if (startActivitySafely(context, systemIntent)) {
                    return OpenFolderResult.SYSTEM_FILES
                }
            }
        } catch (_: Exception) {}

        // 3. In-app guarantee
        return OpenFolderResult.IN_APP_FALLBACK
    }

    fun isSamsungMyFilesInstalled(context: Context): Boolean {
        return isPackageInstalled(context, SAMSUNG_MY_FILES_PACKAGE)
    }

    fun isPackageInstalled(context: Context, packageName: String): Boolean {
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
}
