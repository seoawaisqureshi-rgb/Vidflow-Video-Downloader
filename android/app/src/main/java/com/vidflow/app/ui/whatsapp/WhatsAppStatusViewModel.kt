package com.vidflow.app.ui.whatsapp

import android.app.Application
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.vidflow.app.data.db.AppDatabase
import com.vidflow.app.data.db.DownloadRecord
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.data.model.Platform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream

class WhatsAppStatusViewModel(application: Application) : AndroidViewModel(application) {

    private val prefs = application.getSharedPreferences("whatsapp_status_prefs", Context.MODE_PRIVATE)

    private val _statusItems = MutableStateFlow<List<WhatsAppStatusItem>>(emptyList())
    val statusItems: StateFlow<List<WhatsAppStatusItem>> = _statusItems.asStateFlow()

    private val _savedStatusItems = MutableStateFlow<List<WhatsAppStatusItem>>(emptyList())
    val savedStatusItems: StateFlow<List<WhatsAppStatusItem>> = _savedStatusItems.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private val _showStatusGrid = MutableStateFlow(false)
    val showStatusGrid: StateFlow<Boolean> = _showStatusGrid.asStateFlow()

    private val _selectedTab = MutableStateFlow(0) // 0 = Recent Statuses, 1 = Saved Statuses
    val selectedTab: StateFlow<Int> = _selectedTab.asStateFlow()

    private val _infoMessage = MutableStateFlow<String?>(null)
    val infoMessage: StateFlow<String?> = _infoMessage.asStateFlow()

    private val _discoveryPath = MutableStateFlow<String>("None")
    val discoveryPath: StateFlow<String> = _discoveryPath.asStateFlow()

    init {
        loadSavedStatuses()
        // On first open, auto-scan if a working path (SAF grant or direct scan) previously found statuses.
        // This ensures the user goes directly to the status list on re-open, not back to the grant screen.
        val hadWorkingPath = prefs.getBoolean("had_working_path", false)
        if (hadWorkingPath) {
            loadStatuses()
        }
    }

    fun setSelectedTab(index: Int) {
        _selectedTab.value = index
        if (index == 1) {
            loadSavedStatuses()
        }
    }

    fun clearInfoMessage() {
        _infoMessage.value = null
    }

    /**
     * Checks if a valid SAF tree permission was previously persisted.
     */
    fun hasPersistedPermission(): Boolean {
        val savedUriStr = prefs.getString("tree_uri", null) ?: return false
        val treeUri = runCatching { Uri.parse(savedUriStr) }.getOrNull() ?: return false
        val context = getApplication<Application>()
        val hasPersisted = context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
        return hasPersisted && DocumentsContract.isTreeUri(treeUri)
    }

    fun loadStatuses(forceDirectOnly: Boolean = false) {
        viewModelScope.launch {
            _isLoading.value = true
            _showStatusGrid.value = true
            val (items, pathUsed) = withContext(Dispatchers.IO) {
                scanStatusesInternal(forceDirectOnly)
            }
            _statusItems.value = items
            _discoveryPath.value = pathUsed

            // Persist "working path found" so next open skips the grant screen
            if (items.isNotEmpty() || pathUsed != "None") {
                prefs.edit().putBoolean("had_working_path", true).apply()
            }

            _isLoading.value = false

            // Update seen items set in preferences
            withContext(Dispatchers.IO) {
                val currentSeen = prefs.getStringSet("seen_statuses", emptySet())?.toMutableSet() ?: mutableSetOf()
                items.forEach { currentSeen.add(it.name) }
                prefs.edit().putStringSet("seen_statuses", currentSeen).apply()
            }
        }
    }

    private fun scanStatusesInternal(forceDirectOnly: Boolean): Pair<List<WhatsAppStatusItem>, String> {
        val context = getApplication<Application>()
        val results = mutableListOf<WhatsAppStatusItem>()
        var pathUsed = "None"

        // Load previously seen items for new-item diff tracking (like VidMate's mNewItemStatuses)
        val previouslySeen = prefs.getStringSet("seen_statuses", emptySet()) ?: emptySet()
        val savedNames = prefs.getStringSet("saved_statuses", emptySet()) ?: emptySet()

        // 1. SAF Tree scan (if not forced to direct only)
        if (!forceDirectOnly) {
            val savedUriStr = prefs.getString("tree_uri", null)
            if (!savedUriStr.isNullOrBlank()) {
                val treeUri = runCatching { Uri.parse(savedUriStr) }.getOrNull()
                if (treeUri != null && DocumentsContract.isTreeUri(treeUri)) {
                    val hasPersisted = context.contentResolver.persistedUriPermissions.any {
                        it.uri == treeUri && it.isReadPermission
                    }
                    if (hasPersisted) {
                        try {
                            val docDir = DocumentFile.fromTreeUri(context, treeUri)
                            if (docDir != null && docDir.exists() && docDir.isDirectory) {
                                val targetDir = if (docDir.name?.equals(".Statuses", ignoreCase = true) == true) {
                                    docDir
                                } else {
                                    docDir.findFile(".Statuses") ?: docDir
                                }
                                targetDir.listFiles().forEach { doc ->
                                    val name = doc.name?.lowercase() ?: ""
                                    if (doc.isFile && (name.endsWith(".mp4") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                                        val rawName = doc.name ?: "status_${System.currentTimeMillis()}"
                                        results.add(
                                            WhatsAppStatusItem(
                                                uri = doc.uri,
                                                name = rawName,
                                                isVideo = name.endsWith(".mp4"),
                                                sizeBytes = doc.length(),
                                                lastModified = doc.lastModified(),
                                                isNew = !previouslySeen.contains(rawName),
                                                isSaved = savedNames.contains(rawName),
                                            )
                                        )
                                    }
                                }
                                if (results.isNotEmpty()) {
                                    pathUsed = "SAF Picker (persisted tree URI)"
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.w("VidFlow/WA", "SAF read error: ${e.message}")
                        }
                    } else {
                        prefs.edit().remove("tree_uri").apply()
                    }
                }
            }
        }

        // 2. Direct File scan fallback (Samsung Android or accessible scoped directories)
        if (results.isEmpty()) {
            val possibleDirs = listOf(
                File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
                File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
                File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/.Statuses"),
                File(Environment.getExternalStorageDirectory(), "WhatsApp Business/Media/.Statuses"),
            )

            for (dir in possibleDirs) {
                try {
                    if (dir.exists() && dir.isDirectory) {
                        val files = dir.listFiles()
                        if (files != null && files.isNotEmpty()) {
                            files.forEach { file ->
                                val name = file.name.lowercase()
                                if (file.isFile && (name.endsWith(".mp4") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                                    if (results.none { it.name == file.name }) {
                                        results.add(
                                            WhatsAppStatusItem(
                                                uri = Uri.fromFile(file),
                                                name = file.name,
                                                isVideo = name.endsWith(".mp4"),
                                                sizeBytes = file.length(),
                                                lastModified = file.lastModified(),
                                                isNew = !previouslySeen.contains(file.name),
                                                isSaved = savedNames.contains(file.name),
                                            )
                                        )
                                    }
                                }
                            }
                            if (results.isNotEmpty() && pathUsed == "None") {
                                pathUsed = "Direct File scan: ${dir.path}"
                            }
                        }
                    }
                } catch (_: Exception) {}
            }
        }

        return Pair(results.sortedByDescending { it.lastModified }, pathUsed)
    }

    /**
     * Loads saved WhatsApp statuses from Room DB and downloads folder.
     */
    fun loadSavedStatuses() {
        viewModelScope.launch {
            val items: List<WhatsAppStatusItem> = withContext(Dispatchers.IO) {
                val context = getApplication<Application>()
                val db = AppDatabase.getInstance(context)
                val records: List<DownloadRecord> = db.downloadDao().getByPlatform(Platform.WHATSAPP)
                records.mapNotNull { record: DownloadRecord ->
                    val path = record.filePath ?: return@mapNotNull null
                    val file = File(path)
                    if (file.exists() && file.length() > 0) {
                        WhatsAppStatusItem(
                            uri = Uri.fromFile(file),
                            name = file.name,
                            isVideo = record.ext.equals("mp4", ignoreCase = true),
                            sizeBytes = file.length(),
                            lastModified = file.lastModified(),
                            isNew = false,
                            isSaved = true,
                        )
                    } else null
                }
            }
            _savedStatusItems.value = items
        }
    }

    /**
     * Handles newly granted SAF Tree Uri.
     */
    fun onFolderPermissionResult(uri: Uri?, intentFlags: Int, onComplete: (Boolean, String) -> Unit) {
        if (uri == null) {
            onComplete(false, "No folder selected.")
            return
        }

        val context = getApplication<Application>()
        if (!DocumentsContract.isTreeUri(uri)) {
            onComplete(false, "Selected location is not a folder tree. Please select the folder.")
            return
        }

        val takeFlags = intentFlags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        val finalFlags = if (takeFlags != 0) takeFlags else Intent.FLAG_GRANT_READ_URI_PERMISSION

        try {
            context.contentResolver.takePersistableUriPermission(uri, finalFlags)
            prefs.edit().putString("tree_uri", uri.toString()).apply()
            loadStatuses()
            onComplete(true, "Folder access granted! Statuses loaded.")
        } catch (e: SecurityException) {
            onComplete(false, "Permission could not be saved, please pick the folder again")
        } catch (e: Exception) {
            onComplete(false, "Error saving permission: ${e.message}")
        }
    }

    fun saveStatus(item: WhatsAppStatusItem, onResult: (Boolean, String) -> Unit) {
        viewModelScope.launch {
            val (success, msg) = withContext(Dispatchers.IO) {
                saveStatusInternal(item)
            }
            if (success) {
                loadSavedStatuses()
            }
            onResult(success, msg)
        }
    }

    private suspend fun saveStatusInternal(item: WhatsAppStatusItem): Pair<Boolean, String> {
        val context = getApplication<Application>()
        return try {
            val ext = if (item.isVideo) "mp4" else "jpg"
            val cleanName = item.name.substringBeforeLast(".")
            val fileName = "WA_Status_${System.currentTimeMillis()}_$cleanName.$ext"

            // Open input stream safely handling both file:// and content:// schemes
            val inStream = if (item.uri.scheme == "file") {
                val f = File(item.uri.path ?: "")
                if (!f.exists() || !f.canRead()) return Pair(false, "Cannot read status file on disk")
                FileInputStream(f)
            } else {
                context.contentResolver.openInputStream(item.uri)
            } ?: return Pair(false, "Could not open status file stream")

            var savedFilePath = ""

            // On modern Android (Android 10+ / Q+), write via MediaStore to avoid Scoped Storage EPERM
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val mimeType = if (item.isVideo) "video/mp4" else "image/jpeg"
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidFlow")
                    put(MediaStore.MediaColumns.IS_PENDING, 1)
                }

                val collection = if (item.isVideo) {
                    MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                } else {
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
                }

                val outUri = context.contentResolver.insert(collection, values)
                    ?: return Pair(false, "Could not create MediaStore entry")

                inStream.use { input ->
                    context.contentResolver.openOutputStream(outUri)?.use { output ->
                        input.copyTo(output)
                    } ?: return Pair(false, "Could not write to MediaStore destination")
                }

                values.clear()
                values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                context.contentResolver.update(outUri, values, null, null)

                // Also maintain real filesystem path reference for Player and Room DB
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidFlow")
                val localFile = File(targetDir, fileName)
                savedFilePath = if (localFile.exists()) localFile.absolutePath else outUri.toString()
            } else {
                // Legacy Android (< Android 10)
                val targetDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidFlow")
                if (!targetDir.exists()) targetDir.mkdirs()
                val targetFile = File(targetDir, fileName)

                inStream.use { input ->
                    targetFile.outputStream().use { output ->
                        input.copyTo(output)
                    }
                }
                savedFilePath = targetFile.absolutePath
            }

            // Register in saved_statuses preference
            val savedNames = prefs.getStringSet("saved_statuses", emptySet())?.toMutableSet() ?: mutableSetOf()
            savedNames.add(item.name)
            prefs.edit().putStringSet("saved_statuses", savedNames).apply()

            // Update item in current list as saved
            _statusItems.value = _statusItems.value.map {
                if (it.name == item.name) it.copy(isSaved = true) else it
            }

            // Register in Room DB
            try {
                val db = AppDatabase.getInstance(context)
                val record = DownloadRecord(
                    url = item.uri.toString(),
                    title = "WA_Status_$cleanName",
                    thumbnail = if (!item.isVideo) item.uri.toString() else null,
                    platform = Platform.WHATSAPP,
                    quality = if (item.isVideo) "Video Status" else "Photo Status",
                    ext = ext,
                    filePath = savedFilePath,
                    fileSize = item.sizeBytes,
                    duration = null,
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    completedAt = System.currentTimeMillis()
                )
                db.downloadDao().insert(record)
            } catch (_: Exception) {}

            Pair(true, "Saved to Downloads/VidFlow & My Files!")
        } catch (e: Exception) {
            Pair(false, "Failed to save status: ${e.message}")
        }
    }
}
