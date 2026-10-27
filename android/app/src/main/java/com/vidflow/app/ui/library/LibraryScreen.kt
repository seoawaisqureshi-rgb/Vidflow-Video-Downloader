package com.vidflow.app.ui.library

import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import coil.compose.AsyncImage
import com.vidflow.app.data.db.DownloadRecord
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.theme.*
import com.vidflow.app.ui.history.HistoryViewModel
import java.io.File

private enum class LibraryTab(val label: String) {
    DOWNLOADED("Downloaded"),
    DEVICE("Local Device"),
    PRIVATE_SPACE("Private Space"),
}

data class DeviceMediaItem(
    val id: Long,
    val title: String,
    val uri: Uri,
    val path: String?,
    val isVideo: Boolean,
    val durationMs: Long,
    val sizeBytes: Long,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    historyVm: HistoryViewModel,
    onPlayMedia: (String, String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(LibraryTab.DOWNLOADED) }
    val historyRecords by historyVm.downloads.collectAsState()

    // Local device media query
    var localMediaList by remember { mutableStateOf<List<DeviceMediaItem>>(emptyList()) }
    var isLoadingLocal by remember { mutableStateOf(false) }

    // Private Space state
    val prefs = remember { context.getSharedPreferences("vidflow_vault_prefs", Context.MODE_PRIVATE) }
    var vaultPin by remember { mutableStateOf(prefs.getString("vault_pin", null)) }
    var isVaultUnlocked by remember { mutableStateOf(false) }
    var vaultFiles by remember { mutableStateOf<List<File>>(emptyList()) }

    fun refreshVaultFiles() {
        val vaultDir = File(context.filesDir, "private_vault").apply { mkdirs() }
        vaultFiles = vaultDir.listFiles()?.filter { it.isFile }?.toList() ?: emptyList()
    }

    fun queryLocalMedia() {
        isLoadingLocal = true
        val list = mutableListOf<DeviceMediaItem>()
        val projection = arrayOf(
            MediaStore.MediaColumns._ID,
            MediaStore.MediaColumns.DISPLAY_NAME,
            MediaStore.MediaColumns.DATA,
            MediaStore.MediaColumns.DURATION,
            MediaStore.MediaColumns.SIZE,
        )

        try {
            // Query local videos
            context.contentResolver.query(
                MediaStore.Video.Media.EXTERNAL_CONTENT_URI,
                projection, null, null,
                "${MediaStore.MediaColumns.DATE_ADDED} DESC"
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DISPLAY_NAME)
                val dataCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DATA)
                val durCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.DURATION)
                val sizeCol = cursor.getColumnIndexOrThrow(MediaStore.MediaColumns.SIZE)

                while (cursor.moveToNext() && list.size < 80) {
                    val id = cursor.getLong(idCol)
                    val contentUri = Uri.withAppendedPath(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, id.toString())
                    list.add(
                        DeviceMediaItem(
                            id = id,
                            title = cursor.getString(nameCol) ?: "Video",
                            uri = contentUri,
                            path = cursor.getString(dataCol),
                            isVideo = true,
                            durationMs = cursor.getLong(durCol),
                            sizeBytes = cursor.getLong(sizeCol),
                        )
                    )
                }
            }
        } catch (_: Exception) {}

        localMediaList = list
        isLoadingLocal = false
    }

    LaunchedEffect(selectedTab) {
        if (selectedTab == LibraryTab.DEVICE && localMediaList.isEmpty()) {
            queryLocalMedia()
        } else if (selectedTab == LibraryTab.PRIVATE_SPACE) {
            refreshVaultFiles()
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Library",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
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
            // Tab Selector
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                LibraryTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    Button(
                        onClick = { selectedTab = tab },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Accent else SurfaceVariant,
                            contentColor = if (isSelected) Background else TextPrimary,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.weight(1f).height(38.dp),
                        contentPadding = PaddingValues(horizontal = 4.dp)
                    ) {
                        Text(
                            tab.label,
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            Spacer(Modifier.height(8.dp))

            when (selectedTab) {
                LibraryTab.DOWNLOADED -> {
                    val completed = historyRecords.filter { it.status == DownloadStatus.COMPLETED }
                    if (completed.isEmpty()) {
                        EmptyLibraryPlaceholder(message = "No downloaded media yet. Download videos or MP3 to see them here.")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(completed, key = { it.id }) { record ->
                                DownloadedMediaRow(
                                    record = record,
                                    onPlay = {
                                        val path = record.filePath
                                        if (!path.isNullOrBlank()) {
                                            val isVideo = record.ext.lowercase() != "mp3" && record.ext.lowercase() != "m4a"
                                            onPlayMedia(path, record.title, isVideo)
                                        }
                                    }
                                )
                            }
                        }
                    }
                }

                LibraryTab.DEVICE -> {
                    if (isLoadingLocal) {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator(color = Accent)
                        }
                    } else if (localMediaList.isEmpty()) {
                        EmptyLibraryPlaceholder(message = "No local device videos found.")
                    } else {
                        LazyColumn(
                            contentPadding = PaddingValues(16.dp),
                            verticalArrangement = Arrangement.spacedBy(10.dp),
                            modifier = Modifier.fillMaxSize()
                        ) {
                            items(localMediaList, key = { it.id }) { item ->
                                DeviceMediaRow(
                                    item = item,
                                    onPlay = {
                                        onPlayMedia(item.uri.toString(), item.title, item.isVideo)
                                    }
                                )
                            }
                        }
                    }
                }

                LibraryTab.PRIVATE_SPACE -> {
                    if (!isVaultUnlocked) {
                        VaultUnlockView(
                            hasExistingPin = vaultPin != null,
                            onPinEntered = { pin ->
                                if (vaultPin == null) {
                                    // Setup new PIN
                                    prefs.edit().putString("vault_pin", pin).apply()
                                    vaultPin = pin
                                    isVaultUnlocked = true
                                    refreshVaultFiles()
                                    Toast.makeText(context, "Private Space PIN set!", Toast.LENGTH_SHORT).show()
                                } else if (pin == vaultPin) {
                                    isVaultUnlocked = true
                                    refreshVaultFiles()
                                } else {
                                    Toast.makeText(context, "Incorrect PIN", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    } else {
                        VaultUnlockedView(
                            files = vaultFiles,
                            onLock = { isVaultUnlocked = false },
                            onPlay = { file ->
                                onPlayMedia(file.absolutePath, file.name, !file.name.endsWith(".mp3"))
                            },
                            onImport = {
                                // Import sample or chosen video into private vault
                                val downloadsDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidFlow")
                                val candidates: List<File> = downloadsDir.listFiles()?.filter { it.isFile } ?: emptyList()
                                if (candidates.isNotEmpty()) {
                                    val src = candidates.first()
                                    val vaultDir = File(context.filesDir, "private_vault")
                                    src.copyTo(File(vaultDir, src.name), overwrite = true)
                                    src.delete()
                                    refreshVaultFiles()
                                    Toast.makeText(context, "Moved '${src.name}' into Private Space!", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Download a video first to hide it here", Toast.LENGTH_SHORT).show()
                                }
                            },
                            onExport = { file ->
                                try {
                                    val exportDir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "VidFlow")
                                    exportDir.mkdirs()
                                    file.copyTo(File(exportDir, file.name), overwrite = true)
                                    file.delete()
                                    refreshVaultFiles()
                                    Toast.makeText(context, "Restored '${file.name}' to Downloads/VidFlow", Toast.LENGTH_SHORT).show()
                                } catch (e: Exception) {
                                    Toast.makeText(context, "Export error: ${e.message}", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun EmptyLibraryPlaceholder(message: String) {
    Box(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = TextMuted, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, color = TextSecondary, style = MaterialTheme.typography.bodyMedium, textAlign = TextAlign.Center)
        }
    }
}

@Composable
private fun DownloadedMediaRow(
    record: DownloadRecord,
    onPlay: () -> Unit,
) {
    val isAudio = record.ext.lowercase() == "mp3" || record.ext.lowercase() == "m4a"
    Surface(
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (isAudio) AccentDeep else SurfaceVariant)
            ) {
                if (!record.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = record.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Icon(
                    if (isAudio) Icons.Rounded.MusicNote else Icons.Rounded.PlayArrow,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp).align(Alignment.Center)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    "${record.quality} • ${record.ext.uppercase()}",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayCircleFilled, contentDescription = "Play", tint = Accent, modifier = Modifier.size(32.dp))
            }
        }
    }
}

@Composable
private fun DeviceMediaRow(
    item: DeviceMediaItem,
    onPlay: () -> Unit,
) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth().clickable(onClick = onPlay)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(50.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Icon(
                    Icons.Rounded.Movie,
                    contentDescription = null,
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(22.dp).align(Alignment.Center)
                )
            }
            Spacer(Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                val sizeMb = item.sizeBytes / (1024 * 1024)
                Text(
                    "$sizeMb MB",
                    style = MaterialTheme.typography.labelSmall,
                    color = TextSecondary
                )
            }
            IconButton(onClick = onPlay) {
                Icon(Icons.Rounded.PlayArrow, contentDescription = "Play", tint = Accent)
            }
        }
    }
}

@Composable
private fun VaultUnlockView(
    hasExistingPin: Boolean,
    onPinEntered: (String) -> Unit,
) {
    var pinText by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Box(
            modifier = Modifier
                .size(72.dp)
                .clip(CircleShape)
                .background(AccentDeep),
            contentAlignment = Alignment.Center
        ) {
            Icon(Icons.Rounded.Lock, contentDescription = null, tint = Color.White, modifier = Modifier.size(36.dp))
        }

        Spacer(Modifier.height(18.dp))

        Text(
            if (hasExistingPin) "Enter Private Space PIN" else "Set Up Private Space PIN",
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = TextPrimary
        )

        Spacer(Modifier.height(8.dp))

        Text(
            if (hasExistingPin) "Enter your 4-digit security code to access hidden videos."
            else "Create a 4-digit PIN to lock and hide your private media.",
            style = MaterialTheme.typography.bodySmall,
            color = TextSecondary,
            textAlign = TextAlign.Center
        )

        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = pinText,
            onValueChange = { if (it.length <= 4 && it.all { char -> char.isDigit() }) pinText = it },
            placeholder = { Text("••••", textAlign = TextAlign.Center) },
            singleLine = true,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            shape = RoundedCornerShape(16.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Outline,
                focusedContainerColor = Surface,
                unfocusedContainerColor = Surface,
            ),
            modifier = Modifier.width(180.dp)
        )

        Spacer(Modifier.height(20.dp))

        Button(
            onClick = {
                if (pinText.length == 4) {
                    onPinEntered(pinText)
                }
            },
            enabled = pinText.length == 4,
            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.width(180.dp).height(46.dp)
        ) {
            Text(if (hasExistingPin) "Unlock" else "Set PIN", fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
private fun VaultUnlockedView(
    files: List<File>,
    onLock: () -> Unit,
    onPlay: (File) -> Unit,
    onImport: () -> Unit,
    onExport: (File) -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Rounded.LockOpen, contentDescription = null, tint = Accent, modifier = Modifier.size(24.dp))
                Spacer(Modifier.width(8.dp))
                Text("Private Vault (${files.size})", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = TextPrimary)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                    onClick = onImport,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                    shape = RoundedCornerShape(12.dp),
                    contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                    modifier = Modifier.height(34.dp)
                ) {
                    Icon(Icons.Rounded.Add, contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(4.dp))
                    Text("Hide Video", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                IconButton(onClick = onLock) {
                    Icon(Icons.Rounded.Lock, contentDescription = "Lock", tint = TextMuted)
                }
            }
        }

        Spacer(Modifier.height(14.dp))

        if (files.isEmpty()) {
            Box(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(Icons.Rounded.Shield, contentDescription = null, tint = TextMuted, modifier = Modifier.size(54.dp))
                    Spacer(Modifier.height(10.dp))
                    Text("No hidden videos in vault", color = TextSecondary, style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(6.dp))
                    Text("Tap 'Hide Video' to move a video from Downloads to this secure vault.", color = TextMuted, style = MaterialTheme.typography.bodySmall, textAlign = TextAlign.Center)
                }
            }
        } else {
            LazyColumn(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxSize()) {
                items(files) { file ->
                    Surface(
                        color = Surface,
                        shape = RoundedCornerShape(14.dp),
                        tonalElevation = 2.dp,
                        modifier = Modifier.fillMaxWidth().clickable { onPlay(file) }
                    ) {
                        Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(46.dp)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(SurfaceVariant),
                                contentAlignment = Alignment.Center
                            ) {
                                Icon(Icons.Rounded.Security, contentDescription = null, tint = Accent)
                            }
                            Spacer(Modifier.width(12.dp))
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    file.name,
                                    style = MaterialTheme.typography.bodyMedium,
                                    fontWeight = FontWeight.SemiBold,
                                    color = TextPrimary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis
                                )
                                Spacer(Modifier.height(2.dp))
                                Text(
                                    "${file.length() / (1024 * 1024)} MB • Hidden",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextSecondary
                                )
                            }
                            IconButton(onClick = { onExport(file) }) {
                                Icon(Icons.Rounded.Unarchive, contentDescription = "Restore to Downloads", tint = Accent)
                            }
                        }
                    }
                }
            }
        }
    }
}
