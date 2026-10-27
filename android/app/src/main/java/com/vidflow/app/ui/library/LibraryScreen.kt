package com.vidflow.app.ui.library

import android.net.Uri
import android.provider.MediaStore
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidflow.app.data.db.DownloadRecord
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.theme.*
import com.vidflow.app.ui.components.FormatLabels
import com.vidflow.app.ui.history.HistoryViewModel

private enum class LibraryTab(val label: String) {
    DOWNLOADED("Downloaded"),
    DEVICE("Local Device"),
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

                while (cursor.moveToNext() && list.size < 100) {
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
            // Tab Selector (Downloaded / Local Device)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
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
                            fontSize = 13.sp,
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
            }
        }
    }
}

@Composable
private fun DownloadedMediaRow(
    record: DownloadRecord,
    onPlay: () -> Unit,
) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
            ) {
                if (!record.thumbnail.isNullOrBlank()) {
                    AsyncImage(
                        model = record.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize()
                    )
                } else {
                    Icon(
                        Icons.Rounded.PlayCircle,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(28.dp).align(Alignment.Center)
                    )
                }
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
                Spacer(Modifier.height(3.dp))
                val sizeText = record.fileSize?.takeIf { it > 0 }?.let { FormatLabels.formatBytes(it) } ?: ""
                Text(
                    "${record.platform.displayName} • ${record.quality} ${record.ext.uppercase()} ${if (sizeText.isNotEmpty()) "• $sizeText" else ""}",
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
private fun DeviceMediaRow(
    item: DeviceMediaItem,
    onPlay: () -> Unit,
) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(14.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant)
            ) {
                AsyncImage(
                    model = item.uri,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
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
                Spacer(Modifier.height(3.dp))
                val sizeMb = "%.1f MB".format(item.sizeBytes / (1024.0 * 1024.0))
                val durSec = item.durationMs / 1000
                val durText = "%d:%02d".format(durSec / 60, durSec % 60)
                Text(
                    "$durText • $sizeMb",
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
private fun EmptyLibraryPlaceholder(message: String) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(32.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(Icons.Rounded.VideoLibrary, contentDescription = null, tint = TextMuted, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(12.dp))
            Text(message, style = MaterialTheme.typography.bodyMedium, color = TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
    }
}
