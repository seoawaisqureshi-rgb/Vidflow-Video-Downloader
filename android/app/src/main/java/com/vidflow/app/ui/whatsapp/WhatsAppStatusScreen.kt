package com.vidflow.app.ui.whatsapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.Chat
import androidx.compose.material.icons.automirrored.rounded.OpenInNew
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.vidflow.app.data.db.AppDatabase
import com.vidflow.app.data.db.DownloadRecord
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.data.model.Platform
import com.vidflow.app.theme.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

data class WhatsAppStatusItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
    val isNew: Boolean = false,
    val isSaved: Boolean = false,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppStatusScreen(
    onBack: () -> Unit,
    onPlayMedia: (Uri, String, Boolean) -> Unit = { _, _, _ -> },
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { context.getSharedPreferences("whatsapp_status_prefs", Context.MODE_PRIVATE) }
    var showStatusGrid by remember { mutableStateOf(false) }
    var statusItems by remember { mutableStateOf<List<WhatsAppStatusItem>>(emptyList()) }
    var selectedPreviewItem by remember { mutableStateOf<WhatsAppStatusItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }
    var showPermissionRationaleDialog by remember { mutableStateOf(false) }

    fun hasAllFilesAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            true
        }
    }

    fun requestAllFilesAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            try {
                val intent = Intent(android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:${context.packageName}")
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                } catch (e2: Exception) {
                    Toast.makeText(context, "Could not open storage settings: ${e2.message}", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    fun loadStatuses() {
        isLoading = true
        val list = mutableListOf<WhatsAppStatusItem>()
        val seenSet = prefs.getStringSet("seen_statuses", emptySet()) ?: emptySet()
        val savedSet = prefs.getStringSet("saved_statuses", emptySet()) ?: emptySet()

        // 1. Primary path: Direct File scan (works directly with MANAGE_EXTERNAL_STORAGE on Android 11+, or on Android 10 and below)
        val possibleDirs = listOf(
            File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp/WhatsApp/Media/.Statuses"),
            File(Environment.getExternalStorageDirectory(), "Android/media/com.whatsapp.w4b/WhatsApp Business/Media/.Statuses"),
            File(Environment.getExternalStorageDirectory(), "WhatsApp/Media/.Statuses"),
            File(Environment.getExternalStorageDirectory(), "WhatsApp Business/Media/.Statuses"),
        )

        for (dir in possibleDirs) {
            try {
                if (dir.exists() && dir.isDirectory) {
                    dir.listFiles()?.forEach { file ->
                        val name = file.name.lowercase()
                        if (file.isFile && (name.endsWith(".mp4") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                            // Avoid duplicates
                            if (list.none { it.name == file.name }) {
                                list.add(
                                    WhatsAppStatusItem(
                                        uri = Uri.fromFile(file),
                                        name = file.name,
                                        isVideo = name.endsWith(".mp4"),
                                        sizeBytes = file.length(),
                                        lastModified = file.lastModified(),
                                        isNew = !seenSet.contains(file.name),
                                        isSaved = savedSet.contains(file.name),
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (_: Exception) {
                // Ignore direct file scan error
            }
        }

        // 2. Secondary fallback: check saved SAF permission (Android 11+) if direct scan found nothing
        if (list.isEmpty()) {
            val savedUriStr = prefs.getString("tree_uri", null)
            if (!savedUriStr.isNullOrBlank()) {
                try {
                    val treeUri = Uri.parse(savedUriStr)
                    val hasPersisted = context.contentResolver.persistedUriPermissions.any { it.uri == treeUri && it.isReadPermission }
                    if (!hasPersisted) {
                        prefs.edit().remove("tree_uri").apply()
                    } else {
                        var statusesDir = DocumentFile.fromTreeUri(context, treeUri)
                        if (statusesDir != null && statusesDir.name?.endsWith(".Statuses") != true) {
                            statusesDir = statusesDir.findFile(".Statuses")
                        }
                        statusesDir?.listFiles()?.forEach { doc ->
                            val name = doc.name?.lowercase() ?: ""
                            if (doc.isFile && (name.endsWith(".mp4") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                                val rawName = doc.name ?: "status_${System.currentTimeMillis()}"
                                if (list.none { it.name == rawName }) {
                                    list.add(
                                        WhatsAppStatusItem(
                                            uri = doc.uri,
                                            name = rawName,
                                            isVideo = name.endsWith(".mp4"),
                                            sizeBytes = doc.length(),
                                            lastModified = doc.lastModified(),
                                            isNew = !seenSet.contains(rawName),
                                            isSaved = savedSet.contains(rawName),
                                        )
                                    )
                                }
                            }
                        }
                    }
                } catch (_: Exception) {
                    // Ignore SAF read error
                }
            }
        }

        // Update seen statuses so new ones can be diffed next time
        val updatedSeen = seenSet.toMutableSet()
        list.forEach { updatedSeen.add(it.name) }
        prefs.edit().putStringSet("seen_statuses", updatedSeen).apply()

        statusItems = list.sortedByDescending { it.lastModified }
        isLoading = false
        showStatusGrid = true
    }

    // SAF folder permission launcher for Android 11+
    val folderLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri != null) {
            try {
                context.contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION
                )
                prefs.edit().putString("tree_uri", uri.toString()).apply()
                // Refresh immediately so granted statuses show without tapping "Check Again"
                loadStatuses()
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    // System back must return to the WhatsApp menu: this screen is shown via a
    // state flag (not a nav back stack), so without this the back button does nothing.
    BackHandler(onBack = onBack)

    // Auto-refresh every time the user comes back to this screen (e.g. after viewing
    // statuses in WhatsApp, or right after granting the folder permission), so the
    // list is never stale and nobody gets stuck tapping "Check Again".
    val waActivity = context as? androidx.activity.ComponentActivity
    DisposableEffect(waActivity) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                loadStatuses()
            }
        }
        waActivity?.lifecycle?.addObserver(observer)
        onDispose { waActivity?.lifecycle?.removeObserver(observer) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .clip(CircleShape)
                                .background(Color(0xFF25D366)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.AutoMirrored.Rounded.Chat,
                                contentDescription = null,
                                tint = Color.White,
                                modifier = Modifier.size(18.dp)
                            )
                        }
                        Spacer(Modifier.width(10.dp))
                        Text(
                            "WhatsApp Status Saver",
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                    }
                },
                actions = {
                    if (showStatusGrid) {
                        IconButton(onClick = { loadStatuses() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Refresh", tint = Accent)
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Surface)
            )
        },
        containerColor = Background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (!showStatusGrid) {
                WhatsAppHowItWorksCard(
                    onOpenWhatsApp = {
                        openWhatsAppSafely(context)
                    },
                    onCheckStatuses = {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                            showPermissionRationaleDialog = true
                        } else {
                            loadStatuses()
                        }
                    }
                )
            } else {
                if (isLoading) {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(color = Accent)
                    }
                } else if (statusItems.isEmpty()) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(24.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            Icon(
                                Icons.Rounded.VisibilityOff,
                                contentDescription = null,
                                tint = TextMuted,
                                modifier = Modifier.size(54.dp)
                            )
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "No Viewed Statuses Found",
                                style = MaterialTheme.typography.titleMedium,
                                color = TextPrimary,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(Modifier.height(6.dp))
                            Text(
                                "1. Open WhatsApp and view any status completely.\n2. Come back to VidFlow and tap Refresh Statuses.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))

                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                                Button(
                                    onClick = { showPermissionRationaleDialog = true },
                                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant, contentColor = TextPrimary),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(Icons.Rounded.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp), tint = Accent)
                                    Spacer(Modifier.width(8.dp))
                                    Text("Grant All-Files Storage Access")
                                }
                                Spacer(Modifier.height(10.dp))
                            }

                            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedButton(
                                    onClick = { openWhatsAppSafely(context) },
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Open WhatsApp")
                                }

                                Button(
                                    onClick = {
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !hasAllFilesAccess()) {
                                            showPermissionRationaleDialog = true
                                        } else {
                                            loadStatuses()
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                                    shape = RoundedCornerShape(20.dp)
                                ) {
                                    Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text("Refresh", fontWeight = FontWeight.Bold)
                                }
                            }
                        }
                    }
                } else {
                    LazyVerticalGrid(
                        columns = GridCells.Fixed(3),
                        contentPadding = PaddingValues(10.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(statusItems) { item ->
                            StatusGridItem(
                                item = item,
                                onClick = { selectedPreviewItem = item },
                                onSave = { saveStatus(context, item, scope) }
                            )
                        }
                    }
                }
            }
        }
    }

    // Status Preview Dialog
    selectedPreviewItem?.let { item ->
        StatusPreviewDialog(
            item = item,
            onDismiss = { selectedPreviewItem = null },
            onPlay = {
                onPlayMedia(item.uri, item.name, item.isVideo)
            },
            onSave = {
                saveStatus(context, item, scope)
                selectedPreviewItem = null
            }
        )
    }

    // Storage Permission Rationale Dialog (Android 11+)
    if (showPermissionRationaleDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionRationaleDialog = false },
            title = {
                Text(
                    "All-Files Storage Access",
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )
            },
            text = {
                Text(
                    "VidFlow needs All-Files Storage Access to read viewed WhatsApp and WhatsApp Business status files directly from your storage without requiring folder pickers.\n\nTap Continue to enable access in system settings.",
                    color = TextSecondary,
                    style = MaterialTheme.typography.bodyMedium
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showPermissionRationaleDialog = false
                        requestAllFilesAccess()
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background)
                ) {
                    Text("Continue", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showPermissionRationaleDialog = false }) {
                    Text("Not Now", color = TextMuted)
                }
            },
            containerColor = Surface,
            shape = RoundedCornerShape(20.dp)
        )
    }
}

private fun openWhatsAppSafely(context: Context) {
    val pm = context.packageManager
    val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
    for (pkg in packages) {
        val launchIntent = pm.getLaunchIntentForPackage(pkg)
        if (launchIntent != null) {
            launchIntent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
            context.startActivity(launchIntent)
            return
        }
    }

    // Fallback: try by intent scheme
    try {
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("whatsapp://send"))
        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
        context.startActivity(intent)
        return
    } catch (e: Exception) {
        // Fallback: try launcher category
        try {
            val direct = Intent(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
                setPackage("com.whatsapp")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(direct)
            return
        } catch (e2: Exception) {
            Toast.makeText(context, "WhatsApp is not installed on this device", Toast.LENGTH_SHORT).show()
        }
    }
}

@Composable
private fun WhatsAppHowItWorksCard(
    onOpenWhatsApp: () -> Unit,
    onCheckStatuses: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Surface(
            color = Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF25D366).copy(alpha = 0.15f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.AutoMirrored.Rounded.Chat,
                        contentDescription = null,
                        tint = Color(0xFF25D366),
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(Modifier.height(16.dp))

                Text(
                    "How It Works",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = TextPrimary
                )

                Spacer(Modifier.height(8.dp))

                Text(
                    "Save photos & videos from your friends' WhatsApp statuses directly to your gallery in 2 simple steps:",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextSecondary,
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(20.dp))

                // Step 1
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(AccentGlow),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("1", color = Accent, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Open WhatsApp and view the status you want to save.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }

                Spacer(Modifier.height(14.dp))

                // Step 2
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .size(34.dp)
                            .clip(CircleShape)
                            .background(AccentGlow),
                        contentAlignment = Alignment.Center
                    ) {
                        Text("2", color = Accent, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(12.dp))
                    Text(
                        "Come back to VidFlow and tap Download Statuses.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = TextPrimary
                    )
                }

                Spacer(Modifier.height(24.dp))

                // Step 1 Action Button
                OutlinedButton(
                    onClick = onOpenWhatsApp,
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.AutoMirrored.Rounded.OpenInNew, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("1. Open WhatsApp", fontWeight = FontWeight.SemiBold)
                }

                Spacer(Modifier.height(10.dp))

                // Step 2 Action Button
                Button(
                    onClick = onCheckStatuses,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                    shape = RoundedCornerShape(14.dp),
                    modifier = Modifier.fillMaxWidth().height(48.dp)
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("2. View & Download Statuses", fontWeight = FontWeight.Bold)
                }
            }
        }
    }
}

@Composable
private fun StatusMediaThumbnail(
    item: WhatsAppStatusItem,
    modifier: Modifier = Modifier,
    contentScale: ContentScale = ContentScale.Crop,
) {
    val context = LocalContext.current
    if (item.isVideo) {
        var bitmap by remember(item.uri) { mutableStateOf<Bitmap?>(null) }
        var isExtracting by remember(item.uri) { mutableStateOf(true) }

        LaunchedEffect(item.uri) {
            withContext(Dispatchers.IO) {
                try {
                    val retriever = MediaMetadataRetriever()
                    try {
                        context.contentResolver.openFileDescriptor(item.uri, "r")?.use { pfd ->
                            retriever.setDataSource(pfd.fileDescriptor)
                            bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.frameAtTime
                        }
                    } catch (e: Exception) {
                        if (item.uri.scheme == "file") {
                            retriever.setDataSource(item.uri.path)
                            bitmap = retriever.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC)
                                ?: retriever.frameAtTime
                        }
                    }
                    retriever.release()
                } catch (e: Exception) {
                    // Frame extraction error fallback
                } finally {
                    isExtracting = false
                }
            }
        }

        if (bitmap != null) {
            Image(
                bitmap = bitmap!!.asImageBitmap(),
                contentDescription = null,
                contentScale = contentScale,
                modifier = modifier
            )
        } else {
            Box(
                modifier = modifier.background(SurfaceVariant),
                contentAlignment = Alignment.Center
            ) {
                if (isExtracting) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        strokeWidth = 2.dp,
                        color = Accent
                    )
                } else {
                    Icon(
                        Icons.Rounded.PlayCircleFilled,
                        contentDescription = "Video",
                        tint = Accent,
                        modifier = Modifier.size(36.dp)
                    )
                }
            }
        }
    } else {
        AsyncImage(
            model = item.uri,
            contentDescription = null,
            contentScale = contentScale,
            modifier = modifier
        )
    }
}

@Composable
private fun StatusGridItem(
    item: WhatsAppStatusItem,
    onClick: () -> Unit,
    onSave: () -> Unit,
) {
    Box(
        modifier = Modifier
            .aspectRatio(1f)
            .clip(RoundedCornerShape(12.dp))
            .background(SurfaceVariant)
            .clickable(onClick = onClick)
    ) {
        StatusMediaThumbnail(
            item = item,
            contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize()
        )

        // Video Badge
        if (item.isVideo) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 4.dp, vertical = 2.dp)
            ) {
                Icon(
                    Icons.Rounded.PlayArrow,
                    contentDescription = "Video",
                    tint = Color.White,
                    modifier = Modifier.size(14.dp)
                )
            }
        }

        // New Item Badge
        if (item.isNew) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(6.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(0xFF25D366))
                    .padding(horizontal = 5.dp, vertical = 2.dp)
            ) {
                Text(
                    "NEW",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 9.sp
                )
            }
        }

        // Save icon button
        IconButton(
            onClick = onSave,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(4.dp)
                .size(32.dp)
                .clip(CircleShape)
                .background(Accent.copy(alpha = 0.9f))
        ) {
            Icon(
                Icons.Rounded.Download,
                contentDescription = "Save",
                tint = Background,
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun StatusPreviewDialog(
    item: WhatsAppStatusItem,
    onDismiss: () -> Unit,
    onPlay: () -> Unit,
    onSave: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(20.dp),
            color = Surface,
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(320.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.Black)
                        .clickable(enabled = item.isVideo, onClick = onPlay)
                ) {
                    StatusMediaThumbnail(
                        item = item,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.isVideo) {
                        Box(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .size(64.dp)
                                .clip(CircleShape)
                                .background(Color.Black.copy(alpha = 0.6f)),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                Icons.Rounded.PlayArrow,
                                contentDescription = "Play Video",
                                tint = Color.White,
                                modifier = Modifier.size(42.dp)
                            )
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        if (item.isVideo) "Video Status" else "Photo Status",
                        style = MaterialTheme.typography.titleMedium,
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        "${item.sizeBytes / 1024} KB",
                        style = MaterialTheme.typography.bodySmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Close")
                    }
                    if (item.isVideo) {
                        Button(
                            onClick = onPlay,
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant, contentColor = TextPrimary),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.weight(1f)
                        ) {
                            Icon(Icons.Rounded.PlayArrow, contentDescription = null, modifier = Modifier.size(18.dp), tint = Accent)
                            Spacer(Modifier.width(4.dp))
                            Text("Play")
                        }
                    }
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1.2f)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Save", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun saveStatus(
    context: Context,
    item: WhatsAppStatusItem,
    coroutineScope: kotlinx.coroutines.CoroutineScope
) {
    coroutineScope.launch(Dispatchers.IO) {
        var savedFilePath: String? = null
        try {
            val fileName = "WA_Status_${System.currentTimeMillis()}_${item.name}"
            val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidFlow")
            if (!destDir.exists()) destDir.mkdirs()
            val destFile = File(destDir, fileName)

            // Copy file content directly to Downloads/VidFlow
            context.contentResolver.openInputStream(item.uri)?.use { inStream ->
                destFile.outputStream().use { outStream ->
                    inStream.copyTo(outStream)
                }
            }
            savedFilePath = destFile.absolutePath

            // Also register in MediaStore so Android Gallery immediately scans it
            try {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, if (item.isVideo) "video/mp4" else "image/jpeg")
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                        put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidFlow")
                    } else {
                        put(MediaStore.MediaColumns.DATA, destFile.absolutePath)
                    }
                }
                val targetCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    MediaStore.Downloads.EXTERNAL_CONTENT_URI
                } else {
                    if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                }
                resolver.insert(targetCollection, values)
            } catch (e: Exception) {
                // MediaStore indexing is optional best-effort
            }

            // Insert into Room Database so it appears in My Files -> Completed & All
            try {
                val db = AppDatabase.getInstance(context)
                val record = DownloadRecord(
                    url = item.uri.toString(),
                    title = "WA_Status_${item.name.substringBeforeLast(".")}",
                    thumbnail = if (!item.isVideo) item.uri.toString() else null,
                    platform = Platform.WHATSAPP,
                    quality = if (item.isVideo) "Video Status" else "Photo Status",
                    ext = if (item.isVideo) "mp4" else "jpg",
                    filePath = savedFilePath,
                    fileSize = if (destFile.exists()) destFile.length() else item.sizeBytes,
                    duration = null,
                    status = DownloadStatus.COMPLETED,
                    progress = 100,
                    completedAt = System.currentTimeMillis()
                )
                db.downloadDao().insert(record)
            } catch (e: Exception) {
                // DB insertion best-effort
            }

            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Saved to Downloads/VidFlow & My Files!", Toast.LENGTH_SHORT).show()
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) {
                Toast.makeText(context, "Failed to save: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }
}
