package com.vidflow.app.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.compose.BackHandler
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
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import coil.compose.AsyncImage
import com.vidflow.app.data.extractor.WebViewDetector
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.vidflow.app.theme.*
import com.vidflow.app.ui.components.FormatLabels
import java.net.URLEncoder

data class PlatformSymbol(
    val name: String,
    val iconColor: Color,
    val url: String,
    val isWhatsApp: Boolean = false,
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
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val clipboard = LocalClipboardManager.current

    // Browser navigation & active page state (default home: YouTube)
    var currentBrowserUrl by remember { mutableStateOf("https://m.youtube.com/") }
    var requestedBrowserUrl by remember { mutableStateOf("") }
    var lastLoadedUrl by remember { mutableStateOf("") }
    var currentBrowserTitle by remember { mutableStateOf("YouTube") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isBrowserLoading by remember { mutableStateOf(false) }
    var detectedVideoOnPage by remember { mutableStateOf<String?>(null) }
    var showQualityPickerDialog by remember { mutableStateOf(false) }

    val platforms = remember {
        listOf(
            PlatformSymbol("YouTube", Color(0xFFFF0000), "https://m.youtube.com/"),
            PlatformSymbol("WhatsApp", Color(0xFF25D366), "", isWhatsApp = true),
            PlatformSymbol("Facebook", Color(0xFF1877F2), "https://m.facebook.com/"),
            PlatformSymbol("Instagram", Color(0xFFE1306C), "https://www.instagram.com/"),
            PlatformSymbol("TikTok", Color(0xFF00F2FE), "https://www.tiktok.com/explore"),
            PlatformSymbol("Pinterest", Color(0xFFBD081C), "https://www.pinterest.com/"),
            PlatformSymbol("X", Color(0xFF000000), "https://x.com/"),
            PlatformSymbol("Dailymotion", Color(0xFF0066DC), "https://www.dailymotion.com/"),
            PlatformSymbol("Threads", Color(0xFF181818), "https://www.threads.net/"),
        )
    }

    val detector = remember {
        WebViewDetector { video ->
            detectedVideoOnPage = video.url
        }
    }

    // Android back button: navigate history in WebView, or return to YouTube home
    BackHandler {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else if (currentBrowserUrl != "https://m.youtube.com/") {
            requestedBrowserUrl = "https://m.youtube.com/"
            currentBrowserUrl = "https://m.youtube.com/"
        }
    }

    // Toast messages
    LaunchedEffect(state.snackbarMessage) {
        val msg = state.snackbarMessage ?: return@LaunchedEffect
        Toast.makeText(context, msg, Toast.LENGTH_SHORT).show()
        viewModel.dismissSnackbar()
    }

    // Auto-open quality picker dialog when video info is extracted
    LaunchedEffect(state.videoInfo) {
        if (state.videoInfo != null) {
            showQualityPickerDialog = true
        }
    }

    // Clipboard listener that protects against re-analyzing active jobs
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
            // ========================================================
            // 1. TOP HEADER: Search & URL Bar + Platform Shortcuts Row
            // ========================================================
            Surface(
                color = Surface,
                tonalElevation = 3.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
                    // Search or URL Input Bar
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.urlInput,
                            onValueChange = viewModel::onUrlChanged,
                            placeholder = { Text("Search YouTube or enter URL...", color = TextMuted, fontSize = 13.sp) },
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
                                            requestedBrowserUrl = clean
                                            currentBrowserUrl = clean
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
                            shape = RoundedCornerShape(24.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Outline,
                                focusedContainerColor = Background,
                                unfocusedContainerColor = Background,
                            ),
                            modifier = Modifier
                                .weight(1f)
                                .height(48.dp)
                        )
                        Spacer(Modifier.width(8.dp))
                        Button(
                            onClick = {
                                keyboard?.hide()
                                val input = state.urlInput.trim()
                                if (input.startsWith("http://") || input.startsWith("https://")) {
                                    requestedBrowserUrl = input
                                    currentBrowserUrl = input
                                    viewModel.fetchInfo()
                                } else if (input.isNotBlank()) {
                                    val searchUrl = "https://m.youtube.com/results?search_query=" +
                                        URLEncoder.encode(input, "UTF-8")
                                    requestedBrowserUrl = searchUrl
                                    currentBrowserUrl = searchUrl
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                            shape = RoundedCornerShape(20.dp),
                            contentPadding = PaddingValues(horizontal = 14.dp),
                            modifier = Modifier.height(44.dp)
                        ) {
                            Text("Go", fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(Modifier.height(8.dp))

                    // Platform Shortcuts Row
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(platforms) { platform ->
                            val isSelected = currentBrowserUrl.contains(platform.name, ignoreCase = true)
                            PlatformShortcutCard(
                                symbol = platform,
                                isSelected = isSelected,
                                onClick = {
                                    when {
                                        platform.isWhatsApp -> onOpenWhatsAppSaver()
                                        else -> {
                                            requestedBrowserUrl = platform.url
                                            currentBrowserUrl = platform.url
                                            detectedVideoOnPage = null
                                        }
                                    }
                                }
                            )
                        }
                    }
                }
            }

            // Clipboard Prompt Banner
            state.clipboardUrl?.let { clipUrl ->
                Surface(
                    color = SurfaceVariant,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
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
                        TextButton(onClick = {
                            requestedBrowserUrl = clipUrl
                            currentBrowserUrl = clipUrl
                            viewModel.acceptClipboardUrl()
                            viewModel.fetchInfo()
                        }) {
                            Text("Download", color = Accent, fontWeight = FontWeight.Bold)
                        }
                        IconButton(onClick = viewModel::dismissClipboard, modifier = Modifier.size(24.dp)) {
                            Icon(Icons.Rounded.Close, contentDescription = "Dismiss", tint = TextMuted)
                        }
                    }
                }
            }

            // ========================================================
            // 2. MAIN BROWSER AREA (Always visible, defaults to YouTube)
            // ========================================================
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
                                setSupportMultipleWindows(true)
                                javaScriptCanOpenWindowsAutomatically = true
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                // Clean modern Chrome Android user agent to prevent bot/webview blockage on TikTok, X, IG, FB
                                userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: android.os.Message?
                                ): Boolean {
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport ?: return false
                                    val tempWebView = WebView(view?.context ?: return false).apply {
                                        webViewClient = object : WebViewClient() {
                                            override fun shouldOverrideUrlLoading(v: WebView?, req: WebResourceRequest?): Boolean {
                                                val targetUrl = req?.url?.toString()
                                                if (!targetUrl.isNullOrBlank()) {
                                                    view?.loadUrl(targetUrl)
                                                }
                                                return true
                                            }
                                        }
                                    }
                                    transport.webView = tempWebView
                                    resultMsg.sendToTarget()
                                    return true
                                }
                            }

                            webViewClient = object : WebViewClient() {
                                override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                                    super.onPageStarted(view, url, favicon)
                                    isBrowserLoading = true
                                    detectedVideoOnPage = null
                                    if (view != null && url != null) {
                                        detector.onPageStarted(view, url, favicon)
                                    }
                                }

                                override fun onPageFinished(view: WebView?, url: String?) {
                                    super.onPageFinished(view, url)
                                    isBrowserLoading = false
                                    if (url != null) {
                                        currentBrowserUrl = url
                                        currentBrowserTitle = view?.title ?: "VidFlow"
                                        if (view != null) {
                                            detector.onPageFinished(view, url)
                                        }
                                        if (url.contains("watch?v=") || url.contains("/shorts/") ||
                                            url.contains("/reel/") || url.contains("/p/") ||
                                            url.contains("/video/") || url.contains("/status/") ||
                                            url.contains("/pin/")
                                        ) {
                                            detectedVideoOnPage = url
                                        }
                                    }

                                    // Hide YouTube mobile bottom navigation bar so it does not clash with VidFlow
                                    if (url?.contains("youtube.com") == true) {
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                var style = document.getElementById('vidflow_yt_fix');
                                                if (!style) {
                                                    style = document.createElement('style');
                                                    style.id = 'vidflow_yt_fix';
                                                    style.innerHTML = 'ytm-pivot-bar-renderer, ytm-pivot-bar, .pivot-bar { display: none !important; }';
                                                    document.head.appendChild(style);
                                                }
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val uri = request.url ?: return false
                                    val urlStr = uri.toString()
                                    val scheme = uri.scheme?.lowercase() ?: ""

                                    if (scheme == "http" || scheme == "https") {
                                        return false
                                    }

                                    // Catch intent:// (Instagram, Pinterest, TikTok, X app links)
                                    if (scheme == "intent" || urlStr.startsWith("intent://")) {
                                        try {
                                            val parsedIntent = Intent.parseUri(urlStr, Intent.URI_INTENT_SCHEME)
                                            val fallback = parsedIntent.getStringExtra("browser_fallback_url")
                                            if (!fallback.isNullOrBlank()) {
                                                view.loadUrl(fallback)
                                                return true
                                            }
                                            val dataUri = parsedIntent.data
                                            if (dataUri != null && (dataUri.scheme == "http" || dataUri.scheme == "https")) {
                                                view.loadUrl(dataUri.toString())
                                                return true
                                            }
                                        } catch (_: Exception) {}
                                        return true
                                    }

                                    // Block non-http app schemes (threads://, barcelona://, snssdk://, tiktok://, fb://, etc.)
                                    return true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    detector.shouldInterceptRequest(view, request)
                                    return super.shouldInterceptRequest(view, request)
                                }
                            }

                            webViewRef = this
                            lastLoadedUrl = currentBrowserUrl
                            loadUrl(currentBrowserUrl)
                        }
                    },
                    update = { webView ->
                        if (requestedBrowserUrl.isNotBlank() && requestedBrowserUrl != lastLoadedUrl) {
                            lastLoadedUrl = requestedBrowserUrl
                            webView.loadUrl(requestedBrowserUrl)
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                if (isBrowserLoading) {
                    LinearProgressIndicator(
                        color = Accent,
                        trackColor = Outline,
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.TopCenter)
                    )
                }

                // ========================================================
                // 3. FLOATING DOWNLOAD BUTTON (Sticky prominent pill)
                // ========================================================
                Surface(
                    color = Accent,
                    contentColor = Background,
                    shape = RoundedCornerShape(28.dp),
                    tonalElevation = 8.dp,
                    shadowElevation = 6.dp,
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .padding(bottom = 16.dp)
                        .clickable {
                            // Extract running video or page URL via Javascript
                            webViewRef?.evaluateJavascript(
                                """
                                (function() {
                                    var v = document.querySelector('video');
                                    var vSrc = (v && v.currentSrc) ? v.currentSrc : (v ? v.src : '');
                                    return JSON.stringify({
                                        url: window.location.href,
                                        videoSrc: vSrc
                                    });
                                })()
                                """.trimIndent()
                            ) { jsResult ->
                                val cleaned = jsResult?.replace("\\\"", "\"")?.trim('"', ' ')
                                var targetUrl = currentBrowserUrl
                                if (!cleaned.isNullOrBlank() && cleaned != "null") {
                                    if (cleaned.contains("\"url\":\"")) {
                                        val extracted = cleaned.substringAfter("\"url\":\"").substringBefore("\"")
                                        if (extracted.startsWith("http")) targetUrl = extracted
                                    }
                                }
                                viewModel.onUrlChanged(targetUrl)
                                viewModel.fetchInfo()
                            }
                        }
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 22.dp, vertical = 14.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        if (state.isLoading) {
                            CircularProgressIndicator(
                                color = Background,
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.5.dp
                            )
                            Spacer(Modifier.width(10.dp))
                            Text("Analyzing Video...", fontWeight = FontWeight.Bold, fontSize = 15.sp)
                        } else {
                            Icon(
                                Icons.Rounded.Download,
                                contentDescription = "Download Content",
                                tint = Background,
                                modifier = Modifier.size(22.dp)
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                if (detectedVideoOnPage != null) "Download Video (Ready)" else "Download Video / Audio",
                                fontWeight = FontWeight.Bold,
                                fontSize = 15.sp,
                                color = Background
                            )
                        }
                    }
                }
            }
        }
    }

    // Quality Picker Dialog (1-Tap Download Formats with calculated sizes)
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
private fun PlatformShortcutCard(
    symbol: PlatformSymbol,
    isSelected: Boolean = false,
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(horizontal = 2.dp, vertical = 2.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(symbol.iconColor)
                .then(
                    if (isSelected) Modifier.border(2.dp, Accent, CircleShape) else Modifier
                ),
            contentAlignment = Alignment.Center
        ) {
            when (symbol.name) {
                "YouTube" -> Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                "WhatsApp" -> Icon(Icons.Rounded.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                "Instagram" -> Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                "Facebook" -> Text(
                    "f",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 28.sp,
                )
                "Pinterest" -> Text(
                    "P",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Serif
                )
                "TikTok" -> Icon(Icons.Rounded.MusicNote, contentDescription = null, tint = Color.Black, modifier = Modifier.size(22.dp))
                "X" -> Text(
                    "𝕏",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                "Dailymotion" -> Text(
                    "d",
                    color = Color.White,
                    fontWeight = FontWeight.Black,
                    fontSize = 24.sp,
                    fontFamily = FontFamily.Serif
                )
                "Threads" -> Text(
                    "@",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp,
                )
                else -> Icon(Icons.Rounded.Language, contentDescription = null, tint = Color.White)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            symbol.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
            color = if (isSelected) Accent else TextSecondary,
            fontSize = 11.sp,
            maxLines = 1
        )
    }
}

@Composable
private fun QualityPickerDialog(
    videoInfo: VideoInfo,
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
