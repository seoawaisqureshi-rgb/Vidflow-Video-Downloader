package com.vidflow.app.ui.whatsapp

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
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
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.documentfile.provider.DocumentFile
import coil.compose.AsyncImage
import com.vidflow.app.theme.*
import java.io.File
import java.io.IOException

data class WhatsAppStatusItem(
    val uri: Uri,
    val name: String,
    val isVideo: Boolean,
    val sizeBytes: Long,
    val lastModified: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WhatsAppStatusScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("whatsapp_status_prefs", Context.MODE_PRIVATE) }
    var showStatusGrid by remember { mutableStateOf(false) }
    var statusItems by remember { mutableStateOf<List<WhatsAppStatusItem>>(emptyList()) }
    var selectedPreviewItem by remember { mutableStateOf<WhatsAppStatusItem?>(null) }
    var isLoading by remember { mutableStateOf(false) }

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
            } catch (e: Exception) {
                // Ignore
            }
        }
    }

    fun loadStatuses() {
        isLoading = true
        val list = mutableListOf<WhatsAppStatusItem>()

        // 1. First, check saved SAF permission (Android 11+)
        val savedUriStr = prefs.getString("tree_uri", null)
        if (!savedUriStr.isNullOrBlank()) {
            try {
                val treeUri = Uri.parse(savedUriStr)
                val docDir = DocumentFile.fromTreeUri(context, treeUri)
                docDir?.listFiles()?.forEach { doc ->
                    val name = doc.name?.lowercase() ?: ""
                    if (doc.isFile && (name.endsWith(".mp4") || name.endsWith(".jpg") || name.endsWith(".jpeg") || name.endsWith(".png"))) {
                        list.add(
                            WhatsAppStatusItem(
                                uri = doc.uri,
                                name = doc.name ?: "status_${System.currentTimeMillis()}",
                                isVideo = name.endsWith(".mp4"),
                                sizeBytes = doc.length(),
                                lastModified = doc.lastModified(),
                            )
                        )
                    }
                }
            } catch (e: Exception) {
                // Ignore SAF read error
            }
        }

        // 2. Direct File scan fallback (Works on Android 10 and below, or accessible directories)
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
                                    )
                                )
                            }
                        }
                    }
                }
            } catch (e: Exception) {
                // Ignore
            }
        }

        statusItems = list.sortedByDescending { it.lastModified }
        isLoading = false
        showStatusGrid = true
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
                        loadStatuses()
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
                                "1. Open WhatsApp & view any status completely.\n2. Tap below if folder permission is required.",
                                style = MaterialTheme.typography.bodyMedium,
                                color = TextSecondary,
                                textAlign = TextAlign.Center
                            )
                            Spacer(Modifier.height(16.dp))

                            // Grant permission button for Android 11+
                            Button(
                                onClick = {
                                    val initUri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3AAndroid%2Fmedia%2Fcom.whatsapp%2FWhatsApp%2FMedia%2F.Statuses")
                                    folderLauncher.launch(initUri)
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = SurfaceVariant, contentColor = TextPrimary),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Rounded.FolderShared, contentDescription = null, modifier = Modifier.size(18.dp), tint = Accent)
                                Spacer(Modifier.width(8.dp))
                                Text("Grant Statuses Folder Access")
                            }

                            Spacer(Modifier.height(10.dp))

                            Button(
                                onClick = { loadStatuses() },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                                shape = RoundedCornerShape(20.dp)
                            ) {
                                Icon(Icons.Rounded.Refresh, contentDescription = null, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text("Check Again", fontWeight = FontWeight.Bold)
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
                                onSave = { saveStatus(context, item) }
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
            onSave = {
                saveStatus(context, item)
                selectedPreviewItem = null
            }
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
        AsyncImage(
            model = item.uri,
            contentDescription = null,
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
                ) {
                    AsyncImage(
                        model = item.uri,
                        contentDescription = null,
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxSize()
                    )
                    if (item.isVideo) {
                        Icon(
                            Icons.Rounded.PlayCircleFilled,
                            contentDescription = "Play",
                            tint = Color.White.copy(alpha = 0.8f),
                            modifier = Modifier.size(54.dp).align(Alignment.Center)
                        )
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

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Close")
                    }
                    Button(
                        onClick = onSave,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                        shape = RoundedCornerShape(12.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Download", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

private fun saveStatus(context: Context, item: WhatsAppStatusItem) {
    try {
        val resolver = context.contentResolver
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "status_${System.currentTimeMillis()}_${item.name}")
            put(MediaStore.MediaColumns.MIME_TYPE, if (item.isVideo) "video/mp4" else "image/jpeg")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DOWNLOADS}/VidFlow")
            }
        }
        val targetCollection = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            MediaStore.Downloads.EXTERNAL_CONTENT_URI
        } else {
            if (item.isVideo) MediaStore.Video.Media.EXTERNAL_CONTENT_URI else MediaStore.Images.Media.EXTERNAL_CONTENT_URI
        }
        val targetUri = resolver.insert(targetCollection, values) ?: throw IOException("Could not create MediaStore entry")
        resolver.openInputStream(item.uri)?.use { inStream ->
            resolver.openOutputStream(targetUri)?.use { outStream ->
                inStream.copyTo(outStream)
            }
        }
        Toast.makeText(context, "Saved to Downloads/VidFlow!", Toast.LENGTH_SHORT).show()
    } catch (e: Exception) {
        try {
            val destDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidFlow")
            destDir.mkdirs()
            val destFile = File(destDir, "status_${System.currentTimeMillis()}_${item.name}")
            context.contentResolver.openInputStream(item.uri)?.use { input ->
                destFile.outputStream().use { output ->
                    input.copyTo(output)
                }
            }
            Toast.makeText(context, "Saved to Downloads/VidFlow!", Toast.LENGTH_SHORT).show()
        } catch (e2: Exception) {
            Toast.makeText(context, "Failed to save: ${e2.message}", Toast.LENGTH_LONG).show()
        }
    }
}
