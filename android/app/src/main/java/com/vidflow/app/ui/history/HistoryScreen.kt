package com.vidflow.app.ui.history

import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.vidflow.app.data.db.DownloadRecord
import com.vidflow.app.data.model.DownloadStatus
import com.vidflow.app.theme.*
import com.vidflow.app.ui.components.*

@Composable
fun HistoryScreen(
    viewModel: HistoryViewModel,
    modifier: Modifier = Modifier,
) {
    val downloads by viewModel.downloads.collectAsState()

    Column(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        // Header
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Download History",
                style = MaterialTheme.typography.headlineMedium,
                color = TextPrimary,
            )
            if (downloads.isNotEmpty()) {
                TextButton(onClick = viewModel::clearAll) {
                    Text("Clear All", color = Error)
                }
            }
        }

        if (downloads.isEmpty()) {
            EmptyHistory(modifier = Modifier.fillMaxSize())
        } else {
            LazyColumn(
                contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                items(downloads, key = { it.id }) { record ->
                    HistoryItem(
                        record = record,
                        onDelete = { viewModel.delete(record.id) },
                    )
                }
                item { Spacer(Modifier.height(80.dp)) }
            }
        }
    }
}

@Composable
private fun EmptyHistory(modifier: Modifier = Modifier) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Icon(
            Icons.Rounded.History,
            contentDescription = null,
            tint = TextMuted,
            modifier = Modifier.size(64.dp),
        )
        Spacer(Modifier.height(16.dp))
        Text(
            "No downloads yet",
            style = MaterialTheme.typography.titleMedium,
            color = TextMuted,
        )
        Text(
            "Your download history will appear here",
            style = MaterialTheme.typography.bodyMedium,
            color = TextMuted,
        )
    }
}

@Composable
private fun HistoryItem(
    record: DownloadRecord,
    onDelete: () -> Unit,
) {
    GlassCard(cornerRadius = 12.dp) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Thumbnail
            Box(
                modifier = Modifier
                    .size(72.dp, 52.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                if (record.thumbnail != null) {
                    AsyncImage(
                        model = record.thumbnail,
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                } else {
                    Icon(Icons.Rounded.VideoFile, null, tint = TextMuted, modifier = Modifier.size(28.dp))
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    record.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PlatformBadge(record.platform.displayName)
                    StatusChip(record.status)
                }
                if (record.status == DownloadStatus.FAILED && !record.errorMessage.isNullOrBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        record.errorMessage!!,
                        style = MaterialTheme.typography.labelSmall,
                        color = Error,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (record.status == DownloadStatus.DOWNLOADING) {
                    Spacer(Modifier.height(6.dp))
                    DownloadProgressBar(
                        progress = record.progress / 100f,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            IconButton(onClick = onDelete) {
                Icon(
                    Icons.Rounded.DeleteOutline,
                    contentDescription = "Delete",
                    tint = TextMuted,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun StatusChip(status: DownloadStatus) {
    val (color, label) = when (status) {
        DownloadStatus.COMPLETED     -> Pair(Success, "Done")
        DownloadStatus.DOWNLOADING   -> Pair(AccentDeep, "Downloading")
        DownloadStatus.FAILED        -> Pair(Error, "Failed")
        DownloadStatus.CANCELLED     -> Pair(TextMuted, "Cancelled")
        DownloadStatus.QUEUED        -> Pair(Warning, "Queued")
        DownloadStatus.FETCHING_INFO -> Pair(Accent, "Fetching…")
    }
    Box(
        modifier = Modifier
            .clip(RoundedCornerShape(20.dp))
            .background(color.copy(alpha = 0.15f))
            .padding(horizontal = 8.dp, vertical = 2.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = color,
            fontWeight = FontWeight.SemiBold,
        )
    }
}
