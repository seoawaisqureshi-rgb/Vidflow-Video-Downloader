package com.vidflow.app.ui.home

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.vidflow.app.data.extractor.DetectedVideo
import com.vidflow.app.data.extractor.WebViewDetector
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.service.DownloadEntry
import com.vidflow.app.service.ServiceState
import com.vidflow.app.theme.*
import com.vidflow.app.ui.components.FormatLabels
import com.vidflow.app.ui.components.PlatformBadge

data class PlatformSymbol(
    val name: String,
    val iconColor: Color,
    val url: String,
    val isInstagram: Boolean = false,
    val isWhatsApp: Boolean = false,
    val isThreads: Boolean = false,
)

@SuppressLint("SetJavaScriptEnabled")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    viewModel: HomeViewModel,
    onOpenLogin: () -> Unit = {},
    onOpenWhatsAppSaver: () -> Unit = {},
    onNavigateToSearch: (String) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by viewModel.uiState.collectAsState()
    val activeDownloads by viewModel.activeDownloads.collectAsState()
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current

    // Browser state for embedded view
    var currentBrowserUrl by remember { mutableStateOf("https://m.youtube.com/") }
    var currentBrowserTitle by remember { mutableStateOf("YouTube") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isBrowserLoading by remember { mutableStateOf(false) }
    var detectedVideoOnPage by remember { mutableStateOf<String?>(null) }
    var showInstagramPrompt by remember { mutableStateOf(false) }
    var showQualityPickerDialog by remember { mutableStateOf(false) }

    val detector = remember {
        WebViewDetector { video ->
            detectedVideoOnPage = video.url
        }
    }

    val platforms = remember {
        listOf(
            PlatformSymbol("YouTube", Color(0xFFFF0000), "https://m.youtube.com/"),
            PlatformSymbol("Instagram", Color(0xFFE1306C), "https://www.instagram.com/", isInstagram = true),
            PlatformSymbol("Facebook", Color(0xFF1877F2), "https://m.facebook.com/"),
            PlatformSymbol("WhatsApp", Color(0xFF25D366), "", isWhatsApp = true),
            PlatformSymbol("TikTok", Color(0xFF00F2FE), "https://www.tiktok.com/"),
            PlatformSymbol("X", Color(0xFFFFFFFF), "https://mobile.x.com/"),
            PlatformSymbol("Dailymotion", Color(0xFF0066DC), "https://www.dailymotion.com/"),
            PlatformSymbol("Pinterest", Color(0xFFBD081C), "https://www.pinterest.com/"),
            PlatformSymbol("Reddit", Color(0xFFFF4500), "https://www.reddit.com/"),
            PlatformSymbol("Threads", Color(0xFFE0E0E0), "https://www.threads.net/", isThreads = true),
        )
    }

    // Handle back button for embedded webview
    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    // Toast messages
    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        viewModel.dismissSnackbar()
    }

    // Auto-open quality picker when videoInfo is successfully fetched
    LaunchedEffect(state.videoInfo) {
        if (state.videoInfo != null) {
            showQualityPickerDialog = true
        }
    }

    // Clipboard listener that protects against re-analyzing active jobs (Bug 1 fix)
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

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Background)
    ) {
        Column(modifier = Modifier.fillMaxSize()) {
            // 1. Persistent Top Search Bar
            Surface(
                color = Surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.urlInput,
                            onValueChange = viewModel::onUrlChanged,
                            placeholder = { Text("Search video and download", color = TextMuted, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Search, contentDescription = null, tint = Accent)
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.urlInput.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.onUrlChanged("") }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMuted)
                                        }
                                    }
                                    IconButton(onClick = {
                                        val clean = HomeViewModel.extractHttpUrl(clipboard.getText()?.text)
                                        if (!clean.isNullOrBlank()) {
                                            viewModel.onUrlChanged(clean)
                                            keyboard?.hide()
                                            viewModel.fetchInfo()
                                        } else {
                                            Toast.makeText(context, "No URL in clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = Accent)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(22.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Outline,
                                focusedContainerColor = Background,
                                unfocusedContainerColor = Background,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(50.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                keyboard?.hide()
                                val input = state.urlInput.trim()
                                if (input.startsWith("http://") || input.startsWith("https://")) {
                                    currentBrowserUrl = input
                                    webViewRef?.loadUrl(input)
                                    viewModel.fetchInfo()
                                } else if (input.isNotBlank()) {
                                    onNavigateToSearch(input)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(46.dp)
                        ) {
                            Text("Go", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // 2. Persistent Row of Platform Symbols
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(platforms) { platform ->
                            PlatformSymbolChip(
                                symbol = platform,
                                onClick = {
                                    when {
                                        platform.isWhatsApp -> onOpenWhatsAppSaver()
                                        platform.isInstagram -> showInstagramPrompt = true
                                        else -> {
                                            currentBrowserUrl = platform.url
                                            detectedVideoOnPage = null
                                            webViewRef?.loadUrl(platform.url)
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Clipboard prompt banner if new URL detected
            state.clipboardUrl?.let { clipUrl ->
                Surface(
                    color = SurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Rounded.Link, contentDescription = null, tint = Accent, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            clipUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = TextPrimary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f)
                        )
                        Spacer(Modifier.width(6.dp))
                        TextButton(onClick = viewModel::acceptClipboardUrl) {
                            Text("Download", color = Accent, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = viewModel::dismissClipboard, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = TextMuted)
                        }
                    }
                }
            }

            // 3. Embedded In-App Browser View (Opens YouTube Immediately on launch)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                AndroidView(
                    factory = { ctx ->
                        WebView(ctx).apply {
                            isVerticalScrollBarEnabled = false
                            isHorizontalScrollBarEnabled = false

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                javaScriptCanOpenWindowsAutomatically = true
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            webChromeClient = WebChromeClient()
                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isBrowserLoading = true
                                    detectedVideoOnPage = null
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isBrowserLoading = false
                                    if (url != null) {
                                        currentBrowserUrl = url
                                        currentBrowserTitle = view?.title ?: "Video Page"
                                        // Detect if this page is a downloadable video URL
                                        if (url.contains("watch?v=") || url.contains("/shorts/") || url.contains("/reel/") || url.contains("/p/") || url.contains("/video/") || url.contains("/status/")) {
                                            detectedVideoOnPage = url
                                        }
                                    }
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: android.webkit.WebResourceRequest,
                                ): android.webkit.WebResourceResponse? {
                                    detector.shouldInterceptRequest(view, request)
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }

                            webViewRef = this
                            loadUrl(currentBrowserUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isBrowserLoading) {
                    LinearProgressIndicator(
                        color = Accent,
                        trackColor = Outline,
                        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter)
                    )
                }

                // Floating Download Action Button (VidMate style)
                // Appears whenever a video is detected or on video pages
                val targetDownloadUrl = detectedVideoOnPage ?: currentBrowserUrl
                val isDownloadablePage = targetDownloadUrl.contains("youtube.com") ||
                        targetDownloadUrl.contains("youtu.be") ||
                        targetDownloadUrl.contains("instagram.com") ||
                        targetDownloadUrl.contains("facebook.com") ||
                        targetDownloadUrl.contains("tiktok.com") ||
                        targetDownloadUrl.contains("x.com") ||
                        targetDownloadUrl.contains("twitter.com") ||
                        targetDownloadUrl.contains("threads.net") ||
                        targetDownloadUrl.contains("dailymotion.com") ||
                        targetDownloadUrl.contains("pinterest.com")

                if (isDownloadablePage) {
                    FloatingActionButton(
                        onClick = {
                            val urlToAnalyze = detectedVideoOnPage ?: currentBrowserUrl
                            viewModel.onUrlChanged(urlToAnalyze)
                            viewModel.fetchInfo()
                        },
                        containerColor = Accent,
                        contentColor = Background,
                        shape = CircleShape,
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(20.dp)
                            .size(60.dp)
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(color = Background, modifier = Modifier.size(26.dp), strokeWidth = 3.dp)
                        } else {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = "Download Video",
                                modifier = Modifier.size(30.dp)
                            )
                        }
                    }
                }
            }
        }
    }

    // Instagram "Open Instagram" Dialog (Specification requirement)
    if (showInstagramPrompt) {
        AlertDialog(
            onDismissRequest = { showInstagramPrompt = false },
            title = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(32.dp)
                            .clip(CircleShape)
                            .background(Color(0xFFE1306C)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(18.dp))
                    }
                    Spacer(Modifier.width(10.dp))
                    Text("Instagram", fontWeight = FontWeight.Bold)
                }
            },
            text = {
                Text(
                    "Open Instagram inside VidFlow to browse public posts and reels freely without forcing login. A download button will appear on detected videos.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = TextPrimary
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        showInstagramPrompt = false
                        currentBrowserUrl = "https://www.instagram.com/"
                        detectedVideoOnPage = null
                        webViewRef?.loadUrl("https://www.instagram.com/")
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text("Open Instagram", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { showInstagramPrompt = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Quality Picker Dialog
    if (showQualityPickerDialog && state.videoInfo != null) {
        val info = state.videoInfo!!
        QualityPickerDialog(
            videoInfo = info,
            selectedFormat = state.selectedFormat,
            onFormatSelected = viewModel::onFormatSelected,
            onStartDownload = {
                viewModel.startDownload()
                showQualityPickerDialog = false
            },
            onDismiss = { showQualityPickerDialog = false }
        )
    }
}

@Composable
private fun PlatformSymbolChip(
    symbol: PlatformSymbol,
    onClick: () -> Unit,
) {
    Surface(
        color = SurfaceVariant,
        shape = RoundedCornerShape(16.dp),
        modifier = Modifier.clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(20.dp)
                    .clip(CircleShape)
                    .background(symbol.iconColor),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = symbol.name.take(1),
                    color = if (symbol.iconColor == Color.White) Color.Black else Color.White,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(Modifier.width(6.dp))
            Text(
                symbol.name,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                color = TextPrimary
            )
        }
    }
}

@Composable
private fun QualityPickerDialog(
    videoInfo: com.vidflow.app.data.model.VideoInfo,
    selectedFormat: VideoFormat?,
    onFormatSelected: (VideoFormat) -> Unit,
    onStartDownload: () -> Unit,
    onDismiss: () -> Unit,
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            color = Surface,
            shape = RoundedCornerShape(24.dp),
            tonalElevation = 6.dp,
            modifier = Modifier.fillMaxWidth().wrapContentHeight()
        ) {
            Column(modifier = Modifier.padding(18.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(
                        modifier = Modifier
                            .size(54.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(SurfaceVariant)
                    ) {
                        if (!videoInfo.thumbnail.isNullOrBlank()) {
                            AsyncImage(
                                model = videoInfo.thumbnail,
                                contentDescription = null,
                                contentScale = ContentScale.Crop,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            videoInfo.title,
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(Modifier.height(2.dp))
                        Text(
                            "${videoInfo.platform.displayName} • Select Format",
                            style = MaterialTheme.typography.labelSmall,
                            color = Accent
                        )
                    }
                }

                Spacer(Modifier.height(14.dp))
                HorizontalDivider(color = Outline, thickness = 0.5.dp)
                Spacer(Modifier.height(10.dp))

                Text("Available Qualities", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                Spacer(Modifier.height(8.dp))

                LazyColumn(modifier = Modifier.heightIn(max = 240.dp)) {
                    items(videoInfo.formats) { format ->
                        val isSelected = selectedFormat?.formatId == format.formatId
                        Surface(
                            color = if (isSelected) AccentGlow else SurfaceVariant,
                            shape = RoundedCornerShape(12.dp),
                            border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Accent) else null,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 4.dp)
                                .clickable { onFormatSelected(format) }
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                RadioButton(
                                    selected = isSelected,
                                    onClick = { onFormatSelected(format) },
                                    colors = RadioButtonDefaults.colors(selectedColor = Accent)
                                )
                                Spacer(Modifier.width(8.dp))
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        FormatLabels.qualityLabel(format),
                                        style = MaterialTheme.typography.bodyMedium,
                                        fontWeight = FontWeight.SemiBold,
                                        color = if (isSelected) Accent else TextPrimary
                                    )
                                    Text(
                                        FormatLabels.metaLine(format),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = TextSecondary
                                    )
                                }
                                Text(
                                    FormatLabels.sizeLabel(format),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (isSelected) Accent else TextMuted
                                )
                            }
                        }
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedButton(
                        onClick = onDismiss,
                        shape = RoundedCornerShape(14.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        Text("Cancel")
                    }
                    Button(
                        onClick = onStartDownload,
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                        shape = RoundedCornerShape(14.dp),
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
