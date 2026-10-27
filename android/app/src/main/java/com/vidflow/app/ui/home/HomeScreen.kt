package com.vidflow.app.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.Image
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.ContentPaste
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Stop
import androidx.compose.material.icons.rounded.Timer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.vidflow.app.R
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.vidflow.app.service.DownloadEntry
import com.vidflow.app.service.ServiceState
import com.vidflow.app.theme.Background
import com.vidflow.app.theme.AccentDeep
import com.vidflow.app.theme.AccentGlow
import com.vidflow.app.theme.Error
import com.vidflow.app.theme.ErrorDim
import com.vidflow.app.theme.Outline
import com.vidflow.app.theme.Success
import com.vidflow.app.theme.Surface
import com.vidflow.app.theme.SurfaceVariant
import com.vidflow.app.theme.TextMuted
import com.vidflow.app.theme.TextPrimary
import com.vidflow.app.theme.TextSecondary
import com.vidflow.app.theme.Accent
import com.vidflow.app.ui.components.DownloadProgressBar
import com.vidflow.app.ui.components.FormatLabels
import com.vidflow.app.ui.components.GlassCard
import com.vidflow.app.ui.components.GradientButton
import com.vidflow.app.ui.components.PlatformBadge
import com.vidflow.app.ui.components.ShimmerBox

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    modifier: Modifier = Modifier,
    onOpenLogin: () -> Unit = {},
) {
    val state by viewModel.uiState.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current

    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        android.widget.Toast.makeText(context, msg, android.widget.Toast.LENGTH_SHORT).show()
        viewModel.dismissSnackbar()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner, clipboard) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.onClipboardDetected(clipboard.getText()?.text)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val queue = activeDownloads.values.toList()

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background),
    ) {
        AmbientGlow()

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            item { AppHeader(activeCount = queue.count { it.state is ServiceState.Downloading || it.state is ServiceState.Paused }) }

            item {
                UrlInputCard(
                    url = state.urlInput,
                    onUrlChange = viewModel::onUrlChanged,
                    onFetch = {
                        keyboard?.hide()
                        viewModel.fetchInfo()
                    },
                    onPaste = {
                        val clean = HomeViewModel.extractHttpUrl(clipboard.getText()?.text)
                        if (!clean.isNullOrBlank()) {
                            viewModel.onUrlChanged(clean)
                            keyboard?.hide()
                            viewModel.fetchInfo()
                        }
                    },
                    isLoading = state.isLoading,
                )
            }

            state.clipboardUrl?.let { clipUrl ->
                item {
                    ClipboardBanner(
                        url = clipUrl,
                        onAnalyze = viewModel::acceptClipboardUrl,
                        onDismiss = viewModel::dismissClipboard,
                    )
                }
            }

            if (queue.isNotEmpty()) {
                item {
                    Text(
                        "Active downloads",
                        style = MaterialTheme.typography.titleSmall,
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold,
                    )
                }
                queue.forEach { entry ->
                    item(key = entry.id) {
                        ActiveDownloadCard(
                            entry = entry,
                            onPause = { viewModel.pauseDownload(entry.id) },
                            onResume = { viewModel.resumeDownload(entry.id) },
                            onCancel = { viewModel.cancelDownload(entry.id) },
                            onDismiss = { viewModel.dismissActiveDownload(entry.id) },
                        )
                    }
                }
            }

            state.error?.let { err ->
                item {
                    ErrorBanner(message = err, onDismiss = viewModel::dismissError)
                }
            }

            state.loginRequiredPlatform?.let { platform ->
                item {
                    LoginRequiredBanner(
                        platformName = platform.displayName,
                        onLogin = onOpenLogin,
                        onDismiss = viewModel::clearLoginRequired,
                    )
                }
            }

            if (state.isLoading) {
                item { VideoInfoShimmer() }
            }

            state.videoInfo?.let { info ->
                if (!state.isLoading) {
                    item {
                        AnimatedVisibility(
                            visible = true,
                            enter = slideInVertically(initialOffsetY = { it / 2 }) + fadeIn(),
                        ) {
                            VideoInfoCard(info = info)
                        }
                    }
                    item {
                        FormatSelectionCard(
                            formats = info.formats,
                            selected = state.selectedFormat,
                            onSelect = viewModel::onFormatSelected,
                        )
                    }
                    item {
                        DownloadActionCard(
                            hasFormat = state.selectedFormat != null,
                            onDownload = viewModel::startDownload,
                            onNewLink = viewModel::clearResult,
                        )
                    }
                }
            }

            item { Spacer(Modifier.height(80.dp)) }
        }
    }
}

@Composable
private fun AmbientGlow() {
    Box(Modifier.fillMaxSize()) {
        Box(
            modifier = Modifier
                .size(280.dp)
                .offset(x = 180.dp, y = (-60).dp)
                .clip(CircleShape)
                .background(AccentGlow)
                .blur(100.dp),
        )
        Box(
            modifier = Modifier
                .size(220.dp)
                .align(Alignment.BottomStart)
                .offset(x = (-60).dp, y = 60.dp)
                .clip(CircleShape)
                .background(AccentGlow)
                .blur(80.dp),
        )
    }
}

@Composable
private fun AppHeader(activeCount: Int) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column {
            Text(
                text = "VidFlow",
                style = MaterialTheme.typography.displayMedium,
                fontWeight = FontWeight.ExtraBold,
                color = TextPrimary,
            )
            Text(
                text = "Paste a link. Pick quality. Download.",
                style = MaterialTheme.typography.bodyMedium,
                color = TextSecondary,
            )
        }
        Box {
            Box(
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Background)
                    .border(1.dp, AccentGlow, RoundedCornerShape(14.dp)),
                contentAlignment = Alignment.Center,
            ) {
                // Same artwork as the launcher icon (black tile + green arrow)
                Image(
                    painter = painterResource(id = R.drawable.ic_launcher_foreground),
                    contentDescription = "VidFlow",
                    modifier = Modifier.size(40.dp),
                )
            }
            if (activeCount > 0) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .offset(x = 4.dp, y = (-4).dp)
                        .size(18.dp)
                        .clip(CircleShape)
                        .background(AccentDeep),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        "$activeCount",
                        color = Background,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun UrlInputCard(
    url: String,
    onUrlChange: (String) -> Unit,
    onFetch: () -> Unit,
    onPaste: () -> Unit,
    isLoading: Boolean,
) {
    GlassCard {
        Text(
            "Paste video URL",
            style = MaterialTheme.typography.titleMedium,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 12.dp),
        )

        OutlinedTextField(
            value = url,
            onValueChange = onUrlChange,
            modifier = Modifier.fillMaxWidth(),
            placeholder = {
                Text(
                    "https://youtube.com/watch?v=...",
                    color = TextMuted,
                    fontSize = 14.sp,
                )
            },
            leadingIcon = {
                Icon(Icons.Rounded.Link, contentDescription = null, tint = Accent)
            },
            trailingIcon = {
                if (url.isNotBlank()) {
                    IconButton(onClick = { onUrlChange("") }) {
                        Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMuted)
                    }
                } else {
                    IconButton(onClick = onPaste) {
                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = AccentDeep)
                    }
                }
            },
            keyboardOptions = KeyboardOptions(
                keyboardType = KeyboardType.Uri,
                imeAction = ImeAction.Go,
            ),
            keyboardActions = KeyboardActions(onGo = { onFetch() }),
            singleLine = true,
            shape = RoundedCornerShape(12.dp),
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Accent,
                unfocusedBorderColor = Outline,
                cursorColor = Accent,
                focusedTextColor = TextPrimary,
                unfocusedTextColor = TextPrimary,
                focusedContainerColor = Surface.copy(alpha = 0.5f),
                unfocusedContainerColor = Surface.copy(alpha = 0.3f),
            ),
        )

        Spacer(Modifier.height(12.dp))

        GradientButton(
            text = if (isLoading) "Analyzing..." else "Analyze URL",
            onClick = onFetch,
            enabled = url.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth(),
            icon = if (isLoading) null else {
                { Icon(Icons.Rounded.Search, null, tint = Color.White, modifier = Modifier.size(20.dp)) }
            },
        )

        if (isLoading) {
            Spacer(Modifier.height(12.dp))
            LinearProgressIndicator(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(4.dp)),
                color = Accent,
                trackColor = SurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        SupportedPlatformsRow()
    }
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun SupportedPlatformsRow() {
    val platforms = listOf(
        "YouTube", "TikTok", "Instagram", "Facebook",
        "X / Twitter", "Reddit", "Pinterest",
        "Threads", "Vimeo", "Dailymotion",
    )
    androidx.compose.foundation.layout.FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        platforms.forEach { name ->
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(SurfaceVariant)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
            ) {
                Text(name, style = MaterialTheme.typography.labelSmall, color = TextSecondary)
            }
        }
    }
}

@Composable
private fun ErrorBanner(message: String, onDismiss: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(ErrorDim)
            .border(1.dp, Error.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ErrorOutline, contentDescription = null, tint = Error, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Text(
            message,
            style = MaterialTheme.typography.bodyMedium,
            color = Error,
            modifier = Modifier.weight(1f),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = Error)
        }
    }
}

@Composable
private fun LoginRequiredBanner(
    platformName: String,
    onLogin: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentGlow)
            .border(1.dp, Accent.copy(alpha = 0.4f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Person, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(
                "Login needed for $platformName",
                style = MaterialTheme.typography.labelLarge,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                "Sign in once in the Login tab, then try this link again.",
                style = MaterialTheme.typography.labelSmall,
                color = TextSecondary,
            )
        }
        Spacer(Modifier.width(8.dp))
        androidx.compose.material3.Button(
            onClick = onLogin,
            shape = RoundedCornerShape(10.dp),
        ) {
            Text("Login")
        }
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = TextSecondary)
        }
    }
}

@Composable
private fun VideoInfoShimmer() {
    GlassCard {
        ShimmerBox(modifier = Modifier.fillMaxWidth().height(180.dp), cornerRadius = 10.dp)
        Spacer(Modifier.height(12.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.8f).height(20.dp))
        Spacer(Modifier.height(8.dp))
        ShimmerBox(modifier = Modifier.fillMaxWidth(0.5f).height(16.dp))
    }
}

@Composable
private fun VideoInfoCard(info: VideoInfo) {
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            info.thumbnail?.let { thumb ->
                AsyncImage(
                    model = thumb,
                    contentDescription = info.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier
                        .size(width = 90.dp, height = 70.dp)
                        .clip(RoundedCornerShape(8.dp)),
                )
            }
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlatformBadge(name = info.platform.displayName)
                    info.duration?.let { dur ->
                        MetaChip(icon = Icons.Rounded.Timer, label = formatDuration(dur))
                    }
                }
                Spacer(Modifier.height(4.dp))
                Text(
                    text = info.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                info.uploader?.let { author ->
                    Spacer(Modifier.height(2.dp))
                    Text(
                        text = author,
                        style = MaterialTheme.typography.labelSmall,
                        color = TextMuted,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

@Composable
private fun MetaChip(icon: ImageVector, label: String) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Icon(icon, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun FormatSelectionCard(
    formats: List<VideoFormat>,
    selected: VideoFormat?,
    onSelect: (VideoFormat) -> Unit,
) {
    GlassCard {
        Text(
            "Select quality",
            style = MaterialTheme.typography.titleSmall,
            color = TextPrimary,
            modifier = Modifier.padding(bottom = 10.dp),
        )
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            formats.take(8).forEach { format ->
                val isSelected = format == selected
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .then(
                            if (isSelected) Modifier.background(AccentGlow).border(1.dp, Accent, RoundedCornerShape(10.dp))
                            else Modifier.background(SurfaceVariant)
                        )
                        .clickable { onSelect(format) }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column {
                        Text(
                            format.quality,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (isSelected) Accent else TextPrimary,
                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                        )
                        Text(
                            buildFormatMeta(format),
                            style = MaterialTheme.typography.labelSmall,
                            color = TextMuted,
                        )
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            FormatLabels.sizeLabel(format),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (format.filesize != null) AccentDeep else TextMuted,
                        )
                        if (isSelected) {
                            Icon(Icons.Rounded.CheckCircle, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DownloadActionCard(
    hasFormat: Boolean,
    onDownload: () -> Unit,
    onNewLink: () -> Unit,
) {
    GlassCard {
        GradientButton(
            text = "Download",
            onClick = onDownload,
            enabled = hasFormat,
            modifier = Modifier.fillMaxWidth(),
            icon = { Icon(Icons.Rounded.Download, null, tint = Color.White, modifier = Modifier.size(20.dp)) },
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Saved to Downloads/VidFlow. Queue another quality or paste a new link while this runs.",
            style = MaterialTheme.typography.labelSmall,
            color = TextMuted,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "New link",
            style = MaterialTheme.typography.labelLarge,
            color = AccentDeep,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onNewLink)
                .padding(vertical = 6.dp),
        )
    }
}

@Composable
private fun ClipboardBanner(
    url: String,
    onAnalyze: () -> Unit,
    onDismiss: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(AccentGlow)
            .border(1.dp, Accent.copy(alpha = 0.45f), RoundedCornerShape(12.dp))
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.ContentPaste, contentDescription = null, tint = AccentDeep, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(8.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text("Link found in clipboard", style = MaterialTheme.typography.labelLarge, color = TextPrimary)
            Text(
                url,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            "Analyze",
            color = AccentDeep,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onAnalyze)
                .padding(horizontal = 8.dp, vertical = 6.dp),
        )
        IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = TextMuted)
        }
    }
}

@Composable
private fun ActiveDownloadCard(
    entry: DownloadEntry,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onCancel: () -> Unit,
    onDismiss: () -> Unit,
) {
    val state = entry.state
    val ctx = LocalContext.current
    GlassCard {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Icon(
                when (state) {
                    is ServiceState.Success -> Icons.Rounded.CheckCircle
                    is ServiceState.Error -> Icons.Rounded.ErrorOutline
                    else -> Icons.Rounded.Download
                },
                contentDescription = null,
                tint = when (state) {
                    is ServiceState.Success -> Success
                    is ServiceState.Error -> Error
                    is ServiceState.Paused -> Accent
                    else -> AccentDeep
                },
                modifier = Modifier.size(22.dp),
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    entry.title,
                    style = MaterialTheme.typography.labelLarge,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(6.dp))
                when (state) {
                    is ServiceState.Downloading -> {
                        val pct = if (state.percent < 0) -1f else (state.percent / 100f).coerceIn(0f, 1f)
                        DownloadProgressBar(progress = pct, modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(
                                    when {
                                        state.isMerging -> "Merging... ${state.percent}%"
                                        state.percent in 0..100 -> "${state.percent}%"
                                        else -> "Preparing..."
                                    },
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold,
                                    color = AccentDeep,
                                )
                                if (state.speedKBps > 0) {
                                    Text(
                                        formatSpeed(state.speedKBps),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextMuted,
                                    )
                                }
                            }
                            val sizeText = when {
                                state.totalBytes > 0 -> "${fmtBytes(state.bytesDownloaded)} / ${fmtBytes(state.totalBytes)}"
                                state.bytesDownloaded > 0 -> fmtBytes(state.bytesDownloaded)
                                else -> "-- / --"
                            }
                            Text(
                                sizeText,
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.SemiBold,
                                color = Accent,
                            )
                        }
                    }
                    is ServiceState.Paused -> {
                        DownloadProgressBar(
                            progress = (state.percent / 100f).coerceIn(0f, 1f),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                "Paused • ${state.percent}%",
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold,
                                color = TextMuted,
                            )
                            val sizeText = when {
                                state.totalBytes > 0 -> "${fmtBytes(state.bytesDownloaded)} / ${fmtBytes(state.totalBytes)}"
                                state.bytesDownloaded > 0 -> fmtBytes(state.bytesDownloaded)
                                else -> ""
                            }
                            Text(
                                sizeText,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                            )
                        }
                    }
                    is ServiceState.Success -> {
                        Text("Saved to Downloads/VidFlow", style = MaterialTheme.typography.labelSmall, color = Success)
                    }
                    is ServiceState.Error -> {
                        Text(state.message.take(80), style = MaterialTheme.typography.labelSmall, color = Error)
                    }
                    is ServiceState.Cancelled -> {
                        Text("Cancelled", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                    else -> {
                        Text("Preparing...", style = MaterialTheme.typography.labelSmall, color = TextMuted)
                    }
                }
            }
            when (state) {
                is ServiceState.Downloading -> {
                    IconButton(onClick = onPause, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Pause, contentDescription = "Pause", tint = TextSecondary)
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Cancel", tint = Error)
                    }
                }
                is ServiceState.Paused -> {
                    IconButton(onClick = onResume, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = "Resume", tint = AccentDeep)
                    }
                    IconButton(onClick = onCancel, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Stop, contentDescription = "Cancel", tint = Error)
                    }
                }
                is ServiceState.Success -> {
                    IconButton(onClick = { openDownloadsFolder(ctx) }, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.FolderOpen, contentDescription = "Open folder", tint = AccentDeep)
                    }
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = TextMuted)
                    }
                }
                is ServiceState.Error, is ServiceState.Cancelled -> {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(36.dp)) {
                        Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = TextMuted)
                    }
                }
                else -> Unit
            }
        }
    }
}
private fun progressLabel(percent: Int, downloaded: Long, total: Long): String {
    val size = when {
        total > 0 -> "${fmtBytes(downloaded)} / ${fmtBytes(total)}"
        downloaded > 0 -> fmtBytes(downloaded)
        else -> null
    }
    val pct = if (percent in 0..100) "$percent%" else "Preparing..."
    return if (size != null) "$pct • $size" else pct
}

private fun formatSpeed(speedKBps: Long): String {
    return if (speedKBps >= 1024) "%.1f MB/s".format(speedKBps / 1024.0) else "$speedKBps KB/s"
}

private fun formatDuration(seconds: Long): String {
    val h = seconds / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

private fun fmtBytes(bytes: Long): String {
    val kb = bytes / 1024
    return when {
        bytes >= 1024L * 1024L -> "%.1fMB".format(bytes / (1024.0 * 1024.0))
        kb >= 1 -> "${kb}KB"
        else -> "${bytes}B"
    }
}

private fun formatBytes(bytes: Long): String = fmtBytes(bytes)

private fun buildFormatMeta(format: VideoFormat): String {
    return FormatLabels.metaLine(format)
}

private fun openDownloadsFolder(context: android.content.Context) {
    try {
        val uri = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FVidFlow"
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return
    } catch (_: Exception) {}

    try {
        val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
        context.startActivity(intent)
        return
    } catch (_: Exception) {}

    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "vnd.android.document/directory",
            )
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(context, "Could not open file manager", android.widget.Toast.LENGTH_SHORT).show()
    }
}
