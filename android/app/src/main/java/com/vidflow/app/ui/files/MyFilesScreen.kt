package com.vidflow.app.ui.files

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidflow.app.data.FileManagerHelper
import com.vidflow.app.data.db.DownloadRecord
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.service.DownloadEntry
import com.vidflow.app.service.DownloadService
import com.vidflow.app.service.ServiceState
import com.vidflow.app.theme.*
import com.vidflow.app.ui.components.FormatLabels
import com.vidflow.app.ui.components.PlatformBadge
import com.vidflow.app.ui.history.HistoryViewModel
import com.vidflow.app.ui.player.MediaPlayerManager

private enum class FileFilterTab(val label: String) {
    ALL("All"),
    DOWNLOADING("Downloading"),
    COMPLETED("Completed"),
    FAILED("Failed"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MyFilesScreen(
    historyVm: HistoryViewModel,
    onOpenPlayer: (String, String, Boolean) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val historyRecords by historyVm.downloads.collectAsState()
    val activeDownloadsMap by DownloadService.downloads.collectAsState()
    val activeList = remember(activeDownloadsMap) { activeDownloadsMap.values.toList() }

    var selectedTab by remember { mutableStateOf(FileFilterTab.ALL) }
    var searchQuery by remember { mutableStateOf("") }
    var showDeleteConfirmDialog by remember { mutableStateOf(false) }

    val filteredRecords = remember(historyRecords, selectedTab, searchQuery) {
        historyRecords.filter { record ->
            val matchesQuery = searchQuery.isBlank() || record.title.contains(searchQuery, ignoreCase = true)
            val matchesTab = when (selectedTab) {
                FileFilterTab.ALL -> true
                FileFilterTab.DOWNLOADING -> record.status == DownloadStatus.DOWNLOADING || record.status == DownloadStatus.QUEUED
                FileFilterTab.COMPLETED -> record.status == DownloadStatus.COMPLETED
                FileFilterTab.FAILED -> record.status == DownloadStatus.FAILED || record.status == DownloadStatus.CANCELLED
            }
            matchesQuery && matchesTab
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "My Files",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                actions = {
                    // Folder icon that opens Samsung My Files or user-preferred file manager (Bug 4 fix)
                    IconButton(onClick = { FileManagerHelper.openFolder(context) }) {
                        Icon(
                            Icons.Rounded.FolderOpen,
                            contentDescription = "Open Folder",
                            tint = Accent,
                            modifier = Modifier.size(26.dp)
                        )
                    }
                    if (historyRecords.isNotEmpty()) {
                        IconButton(onClick = { showDeleteConfirmDialog = true }) {
                            Icon(Icons.Rounded.DeleteSweep, contentDescription = "Clear All", tint = TextMuted)
                        }
                    }
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
            // Search Inside Downloads
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                placeholder = { Text("Search inside downloads...", color = TextMuted, fontSize = 14.sp) },
                leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = TextMuted) },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMuted)
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(20.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Accent,
                    unfocusedBorderColor = Outline,
                    focusedContainerColor = Surface,
                    unfocusedContainerColor = Surface,
                ),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp)
            )

            // Filter Tabs
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                FileFilterTab.entries.forEach { tab ->
                    val isSelected = tab == selectedTab
                    val count = when (tab) {
                        FileFilterTab.ALL -> historyRecords.size + activeList.size
                        FileFilterTab.DOWNLOADING -> activeList.size
                        FileFilterTab.COMPLETED -> historyRecords.count { it.status == DownloadStatus.COMPLETED }
                        FileFilterTab.FAILED -> historyRecords.count { it.status == DownloadStatus.FAILED }
                    }
                    Button(
                        onClick = { selectedTab = tab },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Accent else SurfaceVariant,
                            contentColor = if (isSelected) Background else TextPrimary,
                        ),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                        modifier = Modifier.height(34.dp)
                    ) {
                        Text(
                            "${tab.label} ($count)",
                            fontSize = 12.sp,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                        )
                    }
                }
            }

            // Download items list
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // Show Active Downloads first if tab is ALL or DOWNLOADING
                if (selectedTab == FileFilterTab.ALL || selectedTab == FileFilterTab.DOWNLOADING) {
                    items(activeList, key = { it.id }) { activeItem ->
                        ActiveDownloadCard(
                            entry = activeItem,
                            onPause = { DownloadService.pause(context, activeItem.id) },
                            onResume = { DownloadService.resume(context, activeItem.id) },
                            onCancel = { DownloadService.cancel(context, activeItem.id) },
                        )
                    }
                }

                // Show completed / failed history records
                items(filteredRecords, key = { it.id }) { record ->
                    CompletedFileCard(
                        record = record,
                        onPlay = {
                            val path = record.filePath
                            if (!path.isNullOrBlank()) {
                                val isVideo = record.ext.lowercase() != "mp3" && record.ext.lowercase() != "m4a"
                                onOpenPlayer(path, record.title, isVideo)
                            } else {
                                Toast.makeText(context, "File path not found", Toast.LENGTH_SHORT).show()
                            }
                        },
                        onShare = {
                            val path = record.filePath ?: return@CompletedFileCard
                            shareMediaFile(context, path, record.title)
                        },
                        onDelete = { historyVm.delete(record.id) }
                    )
                }

                if (filteredRecords.isEmpty() && (selectedTab != FileFilterTab.DOWNLOADING || activeList.isEmpty())) {
                    item {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 40.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Icon(Icons.Rounded.FolderOff, contentDescription = null, tint = TextMuted, modifier = Modifier.size(48.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("No downloads found", style = MaterialTheme.typography.bodyMedium, color = TextSecondary)
                            }
                        }
                    }
                }
            }
        }
    }

    if (showDeleteConfirmDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteConfirmDialog = false },
            title = { Text("Clear All History?") },
            text = { Text("This will remove all download records from history. Downloaded files on disk remain safe.") },
            confirmButton = {
                TextButton(onClick = {
                    historyVm.clearAll()
                    showDeleteConfirmDialog = false
                }) {
                    Text("Clear All", color = Error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteConfirmDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}

@Composable
private fun ActiveDownloadCard(
    entry: DownloadEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
) {
    val state = entry.state
    Surface(
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    modifier = Modifier
                        .size(44.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(AccentDeep),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.Download, contentDescription = null, tint = Color.White)
                }
                Spacer(Modifier.width(12.dp))
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        entry.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        if (state is ServiceState.Downloading && state.isMerging) "Merging..." else "Downloading...",
                        style = MaterialTheme.typography.bodySmall,
                        color = Accent
                    )
                }
                IconButton(onClick = {
                    if (state is ServiceState.Downloading) onPause() else onResume()
                }) {
                    Icon(
                        if (state is ServiceState.Downloading) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        contentDescription = "Pause/Resume",
                        tint = Accent
                    )
                }
                IconButton(onClick = onCancel) {
                    Icon(Icons.Rounded.Close, contentDescription = "Cancel", tint = Error)
                }
            }

            Spacer(Modifier.height(10.dp))

            when (state) {
                is ServiceState.Downloading -> {
                    LinearProgressIndicator(
                        progress = { state.percent / 100f },
                        color = Accent,
                        trackColor = Outline,
                        modifier = Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(3.dp))
                    )
                    Spacer(Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text("${state.percent}%", style = MaterialTheme.typography.labelSmall, color = TextPrimary)
                        Text(
                            if (state.isMerging) "Merging..." else FormatLabels.speedLabel(state.speedKBps * 1024),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextSecondary
                        )
                    }
                }
                is ServiceState.Paused -> {
                    Text("Paused (${state.percent}%)", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                }
                is ServiceState.Error -> {
                    Text("Error: ${state.message}", style = MaterialTheme.typography.labelSmall, color = Error)
                }
                else -> {}
            }
        }
    }
}

@Composable
private fun CompletedFileCard(
    record: DownloadRecord,
    onPlay: () -> Unit,
    onShare: () -> Unit,
    onDelete: () -> Unit,
) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onPlay)
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariant)
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
                    if (record.ext.lowercase() == "mp3") Icons.Rounded.PlayCircle else Icons.Rounded.PlayArrow,
                    contentDescription = "Play",
                    tint = Color.White.copy(alpha = 0.85f),
                    modifier = Modifier.size(28.dp).align(Alignment.Center)
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PlatformBadge(name = record.platform.displayName)
                    Spacer(Modifier.width(6.dp))
                    Text(
                        "${record.quality} • ${record.ext.uppercase()}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }
            }

            IconButton(onClick = onShare) {
                Icon(Icons.Rounded.Share, contentDescription = "Share", tint = TextSecondary, modifier = Modifier.size(20.dp))
            }
            IconButton(onClick = onDelete) {
                Icon(Icons.Rounded.Delete, contentDescription = "Delete", tint = TextMuted, modifier = Modifier.size(20.dp))
            }
        }
    }
}

private fun shareMediaFile(context: Context, path: String, title: String) {
    try {
        val fileUri = if (path.startsWith("content://")) {
            Uri.parse(path)
        } else {
            Uri.parse("file://$path")
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = if (path.endsWith(".mp3")) "audio/*" else "video/*"
            putExtra(Intent.EXTRA_STREAM, fileUri)
            putExtra(Intent.EXTRA_TEXT, title)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(intent, "Share Media"))
    } catch (e: Exception) {
        Toast.makeText(context, "Cannot share file: ${e.message}", Toast.LENGTH_SHORT).show()
    }
}
