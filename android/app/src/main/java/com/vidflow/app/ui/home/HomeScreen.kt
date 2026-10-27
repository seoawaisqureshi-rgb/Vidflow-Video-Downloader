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
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
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

data class PlatformSymbol(
    val name: String,
    val iconColor: Color,
    val url: String,
    val isInstagram: Boolean = false,
    val isWhatsApp: Boolean = false,
)

data class FeedVideoItem(
    val title: String,
    val channel: String,
    val viewsAndTime: String,
    val duration: String,
    val url: String,
    val thumbnailUrl: String,
    val platform: String = "YouTube",
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

    // Browser navigation state
    var isBrowserActive by remember { mutableStateOf(false) }
    var currentBrowserUrl by remember { mutableStateOf("https://m.youtube.com/") }
    var currentBrowserTitle by remember { mutableStateOf("YouTube") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isBrowserLoading by remember { mutableStateOf(false) }
    var detectedVideoOnPage by remember { mutableStateOf<String?>(null) }
    var showQualityPickerDialog by remember { mutableStateOf(false) }

    // Selected category chip on Home Feed
    var selectedCategory by remember { mutableStateOf("All") }

    val categories = remember {
        listOf("Shorts", "All", "Music", "Trending", "Gaming", "Ringtones", "News")
    }

    val platforms = remember {
        listOf(
            PlatformSymbol("YouTube", Color(0xFFFF0000), "https://m.youtube.com/"),
            PlatformSymbol("WhatsApp", Color(0xFF25D366), "", isWhatsApp = true),
            PlatformSymbol("Facebook", Color(0xFF1877F2), "https://m.facebook.com/"),
            PlatformSymbol("Instagram", Color(0xFFE1306C), "https://www.instagram.com/"),
            PlatformSymbol("Pinterest", Color(0xFFBD081C), "https://www.pinterest.com/"),
            PlatformSymbol("TikTok", Color(0xFF00F2FE), "https://www.tiktok.com/"),
            PlatformSymbol("X", Color(0xFFFFFFFF), "https://mobile.x.com/"),
            PlatformSymbol("Dailymotion", Color(0xFF0066DC), "https://www.dailymotion.com/"),
            PlatformSymbol("Threads", Color(0xFFE0E0E0), "https://www.threads.net/"),
        )
    }

    // Curated Home Feed items (matching VidMate Screenshot 1)
    val feedVideos = remember {
        listOf(
            FeedVideoItem(
                title = "iPhone 17 Pro Max Ringtone | Original iPhone 17 Ringtone 2026",
                channel = "TunesVibess",
                viewsAndTime = "5.5K views • 20h ago",
                duration = "0:06",
                url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ",
                thumbnailUrl = "https://images.unsplash.com/photo-1511707171634-5f897ff02aa9?w=600&auto=format&fit=crop&q=80",
            ),
            FeedVideoItem(
                title = "Motu Patlu - Motu Ka Magical Plant | Full Episode In HD",
                channel = "Sonic Gang Motu Patlu",
                viewsAndTime = "18M views • 8 months ago",
                duration = "22:16",
                url = "https://www.youtube.com/watch?v=jNQXAC9IVRw",
                thumbnailUrl = "https://images.unsplash.com/photo-1578632767115-351597cf2477?w=600&auto=format&fit=crop&q=80",
            ),
            FeedVideoItem(
                title = "Guzarish - Official Music Video | Satinder Sartaaj",
                channel = "Speed Records",
                viewsAndTime = "115M views • 1 year ago",
                duration = "4:42",
                url = "https://www.youtube.com/watch?v=kXYiU_JCYtU",
                thumbnailUrl = "https://images.unsplash.com/photo-1514525253161-7a46d19cd819?w=600&auto=format&fit=crop&q=80",
            ),
            FeedVideoItem(
                title = "Top 10 AI Breakthroughs Transforming 2026",
                channel = "TechPulse Media",
                viewsAndTime = "420K views • 3 days ago",
                duration = "14:15",
                url = "https://www.youtube.com/watch?v=L_LUpnjgPso",
                thumbnailUrl = "https://images.unsplash.com/photo-1618005182384-a83a8bd57fbe?w=600&auto=format&fit=crop&q=80",
            ),
            FeedVideoItem(
                title = "Relaxing 4K Nature Visuals & Ambient Soundscape",
                channel = "Earth Cinematic",
                viewsAndTime = "2.4M views • 5 months ago",
                duration = "8:30",
                url = "https://www.youtube.com/watch?v=2OEL4P1Rz04",
                thumbnailUrl = "https://images.unsplash.com/photo-1507525428034-b723cf961d3e?w=600&auto=format&fit=crop&q=80",
            ),
        )
    }

    val detector = remember {
        WebViewDetector { video ->
            detectedVideoOnPage = video.url
        }
    }

    // Handle back button: go back in webview, or return to home feed
    BackHandler(enabled = isBrowserActive) {
        if (webViewRef?.canGoBack() == true) {
            webViewRef?.goBack()
        } else {
            isBrowserActive = false
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
            if (!isBrowserActive) {
                // ==========================================
                // 1. HOME FEED VIEW (VidMate Style - Screen 1)
                // ==========================================

                // Search Bar Header
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
                                placeholder = { Text("Search or enter url", color = TextMuted, fontSize = 14.sp) },
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
                                shape = RoundedCornerShape(24.dp),
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
                                        isBrowserActive = true
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

                        // Category Chips Row (Shorts, All, Music, Trending, etc.)
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(categories) { category ->
                                val isSelected = category == selectedCategory
                                val isShorts = category == "Shorts"

                                Surface(
                                    color = if (isSelected) AccentGlow else SurfaceVariant,
                                    shape = RoundedCornerShape(16.dp),
                                    border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Accent) else null,
                                    modifier = Modifier.clickable {
                                        selectedCategory = category
                                        if (isShorts) {
                                            currentBrowserUrl = "https://m.youtube.com/shorts"
                                            isBrowserActive = true
                                        }
                                    }
                                ) {
                                    Row(
                                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        if (isShorts) {
                                            Icon(
                                                Icons.Rounded.PlayArrow,
                                                contentDescription = null,
                                                tint = Color.Red,
                                                modifier = Modifier.size(16.dp)
                                            )
                                            Spacer(Modifier.width(4.dp))
                                        }
                                        Text(
                                            category,
                                            style = MaterialTheme.typography.labelMedium,
                                            fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                                            color = if (isSelected) Accent else TextPrimary
                                        )
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(10.dp))

                        // Platform Shortcuts Row
                        LazyRow(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            items(platforms) { platform ->
                                PlatformShortcutCard(
                                    symbol = platform,
                                    onClick = {
                                        when {
                                            platform.isWhatsApp -> onOpenWhatsAppSaver()
                                            else -> {
                                                currentBrowserUrl = platform.url
                                                detectedVideoOnPage = null
                                                isBrowserActive = true
                                                webViewRef?.loadUrl(platform.url)
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

                // Feed of Videos (with 1-Tap Download Buttons on Each Card)
                LazyColumn(
                    contentPadding = PaddingValues(horizontal = 14.dp, vertical = 10.dp),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(feedVideos) { video ->
                        FeedVideoCard(
                            video = video,
                            onWatch = {
                                currentBrowserUrl = video.url
                                isBrowserActive = true
                                webViewRef?.loadUrl(video.url)
                            },
                            onDownload = {
                                viewModel.onUrlChanged(video.url)
                                viewModel.fetchInfo()
                            }
                        )
                    }
                }
            } else {
                // ==========================================
                // 2. IN-APP VIDEO BROWSER VIEW
                // ==========================================
                Surface(
                    color = Surface,
                    tonalElevation = 3.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp, vertical = 8.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            if (webViewRef?.canGoBack() == true) {
                                webViewRef?.goBack()
                            } else {
                                isBrowserActive = false
                            }
                        }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, contentDescription = "Back", tint = TextPrimary)
                        }

                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                currentBrowserTitle,
                                style = MaterialTheme.typography.titleSmall,
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                currentBrowserUrl,
                                style = MaterialTheme.typography.labelSmall,
                                color = TextMuted,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }

                        IconButton(onClick = { webViewRef?.reload() }) {
                            Icon(Icons.Rounded.Refresh, contentDescription = "Reload", tint = Accent)
                        }

                        IconButton(onClick = { isBrowserActive = false }) {
                            Icon(Icons.Rounded.Close, contentDescription = "Close", tint = TextMuted)
                        }
                    }
                }

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
                                    userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                                }

                                val cookieManager = CookieManager.getInstance()
                                cookieManager.setAcceptCookie(true)
                                cookieManager.setAcceptThirdPartyCookies(this, true)

                                webChromeClient = object : WebChromeClient() {
                                    // Handle "Sign in", "Log in with Google/Facebook", and popup windows
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
                                    }

                                    override fun onPageFinished(view: WebView?, url: String?) {
                                        super.onPageFinished(view, url)
                                        isBrowserLoading = false
                                        if (url != null) {
                                            currentBrowserUrl = url
                                            currentBrowserTitle = view?.title ?: "Content Browser"
                                            if (url.contains("watch?v=") || url.contains("/shorts/") || url.contains("/reel/") || url.contains("/p/") || url.contains("/video/") || url.contains("/status/") || url.contains("/pin/")) {
                                                detectedVideoOnPage = url
                                            }
                                        }

                                        // CSS INJECTION: Suppress target app internal bottom bars, upsell nag banners & full-page barriers
                                        // Ensures VidFlow controls remain in charge of the experience
                                        view?.evaluateJavascript(
                                            """
                                            (function() {
                                                var style = document.getElementById('vidflow_cleanup_css');
                                                if (!style) {
                                                    style = document.createElement('style');
                                                    style.id = 'vidflow_cleanup_css';
                                                    style.innerHTML = `
                                                        /* YouTube mobile menu suppression */
                                                        ytm-pivot-bar-renderer, ytm-pivot-bar, .pivot-bar, [role="tablist"], ytm-mobile-topbar-renderer { display: none !important; }

                                                        /* Instagram app nag & footer popups */
                                                        div._a9-z, div[style*="position: fixed; bottom: 0"], div[role="dialog"] button:has-text("Open App") { display: none !important; }

                                                        /* Facebook mobile app banners */
                                                        .m-banner, ._52z5, #header-banner, [data-sigil="m-banner"] { display: none !important; }

                                                        /* Pinterest full-page barriers & app nag */
                                                        div[data-test-id="full-page-signup-barrier"],
                                                        div[data-test-id="nudge-bar"],
                                                        div[data-test-id="gift-wrap"],
                                                        div[data-test-id="mobile-app-upsell-banner"],
                                                        div[data-test-id="bottom-sheet-container"] { display: none !important; }

                                                        /* Allow scrolling freely */
                                                        html, body { overflow: auto !important; position: static !important; }
                                                    `;
                                                    document.head.appendChild(style);
                                                }
                                            })();
                                            """.trimIndent(),
                                            null
                                        )
                                    }

                                    override fun shouldOverrideUrlLoading(
                                        view: WebView,
                                        request: WebResourceRequest,
                                    ): Boolean {
                                        val uri = request.url ?: return false
                                        val urlStr = uri.toString()
                                        val scheme = uri.scheme?.lowercase() ?: ""

                                        // Catch intent:// (Instagram/Pinterest applink fix to prevent ERR_UNKNOWN_URL_SCHEME)
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
                                            } catch (e: Exception) {
                                                // ignore
                                            }
                                            return true // Handled! Don't let WebView crash
                                        }

                                        // Block non-http schemes like market://, whatsapp:// from crashing WebView
                                        if (scheme != "http" && scheme != "https") {
                                            return true
                                        }

                                        return false
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
                                loadUrl(currentBrowserUrl)
                            }
                        },
                        update = { webView ->
                            if (webView.url != currentBrowserUrl && !currentBrowserUrl.isBlank()) {
                                webView.loadUrl(currentBrowserUrl)
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

                    // VIDFLOW CONTENT DOWNLOAD BAR (Sticky prominent pill button)
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
                                CircularProgressIndicator(color = Background, modifier = Modifier.size(20.dp), strokeWidth = 2.5.dp)
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
                                    "Download Video / Audio",
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
    }

    // Quality Picker Dialog (1-Tap Download Formats)
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
    onClick: () -> Unit,
) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .clickable(onClick = onClick)
            .padding(4.dp)
    ) {
        Box(
            modifier = Modifier
                .size(46.dp)
                .clip(CircleShape)
                .background(symbol.iconColor),
            contentAlignment = Alignment.Center
        ) {
            when {
                symbol.name == "YouTube" -> Icon(Icons.Rounded.PlayArrow, contentDescription = null, tint = Color.White, modifier = Modifier.size(24.dp))
                symbol.name == "WhatsApp" -> Icon(Icons.Rounded.Phone, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                symbol.name == "Instagram" -> Icon(Icons.Rounded.CameraAlt, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                symbol.name == "Facebook" -> Icon(Icons.Rounded.Share, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                symbol.name == "Pinterest" -> Icon(Icons.Rounded.PushPin, contentDescription = null, tint = Color.White, modifier = Modifier.size(22.dp))
                else -> Text(symbol.name.take(1), color = Color.White, fontWeight = FontWeight.Bold, fontSize = 16.sp)
            }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            symbol.name,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Medium,
            color = TextSecondary,
            fontSize = 11.sp
        )
    }
}

@Composable
private fun FeedVideoCard(
    video: FeedVideoItem,
    onWatch: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Video Thumbnail with Duration Badge and Play Overlay
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(190.dp)
                    .clip(RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp))
                    .background(SurfaceVariant)
                    .clickable(onClick = onWatch)
            ) {
                AsyncImage(
                    model = video.thumbnailUrl,
                    contentDescription = video.title,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )

                // Play Button Overlay
                Box(
                    modifier = Modifier
                        .size(50.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.55f))
                        .align(Alignment.Center),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.PlayArrow,
                        contentDescription = "Play",
                        tint = Color.White,
                        modifier = Modifier.size(30.dp)
                    )
                }

                // Duration Badge
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(8.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.8f))
                        .padding(horizontal = 6.dp, vertical = 2.dp)
                ) {
                    Text(
                        video.duration,
                        color = Color.White,
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Details and Direct Download Button Row
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Channel Avatar Icon
                Box(
                    modifier = Modifier
                        .size(38.dp)
                        .clip(CircleShape)
                        .background(AccentGlow),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        Icons.Rounded.Person,
                        contentDescription = null,
                        tint = Accent,
                        modifier = Modifier.size(20.dp)
                    )
                }

                Spacer(Modifier.width(10.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        video.title,
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                    Spacer(Modifier.height(2.dp))
                    Text(
                        "${video.channel} • ${video.viewsAndTime}",
                        style = MaterialTheme.typography.labelSmall,
                        color = TextSecondary
                    )
                }

                Spacer(Modifier.width(8.dp))

                // Direct Download Button on Card (VidMate style)
                IconButton(
                    onClick = onDownload,
                    modifier = Modifier
                        .size(42.dp)
                        .clip(CircleShape)
                        .background(Accent)
                ) {
                    Icon(
                        Icons.Rounded.Download,
                        contentDescription = "Download Video",
                        tint = Background,
                        modifier = Modifier.size(22.dp)
                    )
                }
            }
        }
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
