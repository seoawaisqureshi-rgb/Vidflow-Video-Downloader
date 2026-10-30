package com.vidflow.app.ui.home

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.net.Uri
import android.net.http.SslError
import android.os.Message
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.SslErrorHandler
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
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.model.VideoFormat
import com.vidflow.app.data.model.VideoInfo
import com.vidflow.app.service.DownloadService
import com.vidflow.app.theme.*
import com.vidflow.app.ui.components.FormatLabels
import java.net.URLEncoder

data class PlatformSymbol(
    val name: String,
    val iconColor: Color,
    val url: String = "",
    val isWhatsApp: Boolean = false,
    val isYouTube: Boolean = false,
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
    var interceptedMediaUrl by remember { mutableStateOf<String?>(null) }
    var fallbackVideoInfo by remember { mutableStateOf<VideoInfo?>(null) }
    var showQualityPickerDialog by remember { mutableStateOf(false) }

    val platforms = remember {
        listOf(
            PlatformSymbol("YouTube", Color(0xFFFF0000), "https://m.youtube.com/", isYouTube = true),
            PlatformSymbol("WhatsApp", Color(0xFF25D366), "", isWhatsApp = true),
        )
    }

    val detector = remember {
        WebViewDetector { video ->
            interceptedMediaUrl = video.url
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
            fallbackVideoInfo = null
            showQualityPickerDialog = true
        }
    }

    // Handle extraction error: show actual error, never synthesize YouTube fallback from background browser
    LaunchedEffect(state.error) {
        val err = state.error ?: return@LaunchedEffect
        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
        viewModel.dismissError()
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
                    // 1. YouTube Dedicated URL Bar (Fixed for YouTube browsing & direct links)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.youtubeUrlInput,
                            onValueChange = viewModel::onYoutubeUrlChanged,
                            placeholder = { Text("YouTube link or search video...", color = TextMuted, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Rounded.PlayArrow, contentDescription = "YouTube", tint = Color(0xFFFF0000))
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.youtubeUrlInput.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.onYoutubeUrlChanged("") }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMuted)
                                        }
                                    }
                                    IconButton(onClick = {
                                        val clean = HomeViewModel.extractHttpUrl(clipboard.getText()?.text)
                                        if (!clean.isNullOrBlank()) {
                                            if (HomeViewModel.isYouTubeUrl(clean)) {
                                                viewModel.onYoutubeUrlChanged(clean)
                                            } else {
                                                viewModel.onOtherUrlChanged(clean)
                                            }
                                            keyboard?.hide()
                                        } else {
                                            Toast.makeText(context, "No URL in clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = Accent)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF0000),
                                unfocusedBorderColor = Outline,
                                focusedContainerColor = Background,
                                unfocusedContainerColor = Background,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // 2. Other Platforms Link Bar (Facebook, Instagram, TikTok, X, Pinterest, etc.)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = state.otherUrlInput,
                            onValueChange = viewModel::onOtherUrlChanged,
                            placeholder = { Text("Paste other platform link (FB, IG, TikTok, X...)", color = TextMuted, fontSize = 13.sp) },
                            leadingIcon = {
                                Icon(Icons.Rounded.Link, contentDescription = "Other Platforms", tint = Accent)
                            },
                            trailingIcon = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    if (state.otherUrlInput.isNotEmpty()) {
                                        IconButton(onClick = { viewModel.onOtherUrlChanged("") }) {
                                            Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMuted)
                                        }
                                    }
                                    IconButton(onClick = {
                                        val clean = HomeViewModel.extractHttpUrl(clipboard.getText()?.text)
                                        if (!clean.isNullOrBlank()) {
                                            if (HomeViewModel.isYouTubeUrl(clean)) {
                                                viewModel.onYoutubeUrlChanged(clean)
                                            } else {
                                                viewModel.onOtherUrlChanged(clean)
                                            }
                                            keyboard?.hide()
                                        } else {
                                            Toast.makeText(context, "No URL in clipboard", Toast.LENGTH_SHORT).show()
                                        }
                                    }) {
                                        Icon(Icons.Rounded.ContentPaste, contentDescription = "Paste", tint = Accent)
                                    }
                                }
                            },
                            singleLine = true,
                            shape = RoundedCornerShape(20.dp),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Accent,
                                unfocusedBorderColor = Outline,
                                focusedContainerColor = Background,
                                unfocusedContainerColor = Background,
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(46.dp)
                        )
                    }

                    Spacer(Modifier.height(6.dp))

                    // 3. Single Unified Process Button ("Analyze & Download Options")
                    Button(
                        onClick = {
                            keyboard?.hide()
                            val yt = state.youtubeUrlInput.trim()
                            val other = state.otherUrlInput.trim()

                            if (other.isNotBlank()) {
                                viewModel.fetchInfo()
                            } else if (yt.isNotBlank()) {
                                if (yt.startsWith("http://") || yt.startsWith("https://")) {
                                    requestedBrowserUrl = yt
                                    currentBrowserUrl = yt
                                    viewModel.fetchInfo()
                                } else {
                                    val searchUrl = "https://m.youtube.com/results?search_query=" +
                                        URLEncoder.encode(yt, "UTF-8")
                                    requestedBrowserUrl = searchUrl
                                    currentBrowserUrl = searchUrl
                                }
                            } else {
                                Toast.makeText(context, "Please enter a YouTube link or paste other platform link", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                        shape = RoundedCornerShape(16.dp),
                        contentPadding = PaddingValues(horizontal = 14.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(40.dp)
                    ) {
                        Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(8.dp))
                        Text(
                            if (state.isLoading) "Analyzing Video..." else "Analyze & Download Options",
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp
                        )
                    }

                    Spacer(Modifier.height(8.dp))

                    // Platform Shortcuts Row
                    LazyRow(
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        items(platforms) { platform ->
                            PlatformShortcutCard(
                                symbol = platform,
                                isSelected = platform.isYouTube && currentBrowserUrl.contains("youtube.com"),
                                onClick = {
                                    if (platform.isWhatsApp) {
                                        onOpenWhatsAppSaver()
                                    } else if (platform.isYouTube) {
                                        requestedBrowserUrl = "https://m.youtube.com/"
                                        currentBrowserUrl = "https://m.youtube.com/"
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
                            overScrollMode = WebView.OVER_SCROLL_NEVER

                            settings.apply {
                                javaScriptEnabled = true
                                domStorageEnabled = true
                                databaseEnabled = true
                                setSupportZoom(true)
                                builtInZoomControls = true
                                displayZoomControls = false
                                setSupportMultipleWindows(false) // Single window browsing prevents blank OAuth screens & native crashes
                                javaScriptCanOpenWindowsAutomatically = true
                                mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
                                allowFileAccess = true
                                allowContentAccess = true
                                loadWithOverviewMode = true
                                useWideViewPort = true

                                // Standard Chrome Mobile UA (uses official frozen 'K' format accepted by FB, IG, TikTok, Threads, Pinterest)
                                userAgentString = "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                            }

                            val cookieManager = CookieManager.getInstance()
                            cookieManager.setAcceptCookie(true)
                            cookieManager.setAcceptThirdPartyCookies(this, true)

                            webChromeClient = object : WebChromeClient() {
                                override fun onCreateWindow(
                                    view: WebView?,
                                    isDialog: Boolean,
                                    isUserGesture: Boolean,
                                    resultMsg: Message?
                                ): Boolean {
                                    // Bulletproof window.open handling: route popups to main WebView safely via temp transport
                                    val ctx = view?.context ?: return false
                                    val tempWebView = WebView(ctx)
                                    tempWebView.webViewClient = object : WebViewClient() {
                                        override fun shouldOverrideUrlLoading(v: WebView, req: WebResourceRequest): Boolean {
                                            val target = req.url?.toString()
                                            if (!target.isNullOrBlank()) {
                                                view.loadUrl(target)
                                            }
                                            return true
                                        }
                                    }
                                    val transport = resultMsg?.obj as? WebView.WebViewTransport
                                    transport?.webView = tempWebView
                                    resultMsg?.sendToTarget()
                                    return true
                                }

                                override fun onConsoleMessage(consoleMessage: ConsoleMessage?): Boolean {
                                    val msg = consoleMessage?.message() ?: ""
                                    if (msg.startsWith("VIDFLOW_VIDEO:")) {
                                        val videoSrc = msg.removePrefix("VIDFLOW_VIDEO:")
                                        if (videoSrc.startsWith("http://") || videoSrc.startsWith("https://")) {
                                            interceptedMediaUrl = videoSrc
                                        }
                                    }
                                    return super.onConsoleMessage(consoleMessage)
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
                                    CookieManager.getInstance().flush()

                                    if (url != null) {
                                        currentBrowserUrl = url
                                        currentBrowserTitle = view?.title ?: "VidFlow"
                                        if (view != null) {
                                            detector.onPageFinished(view, url)
                                        }
                                        val lower = url.lowercase()
                                        if (lower.contains("watch?v=") || lower.contains("/shorts/") ||
                                            lower.contains("/reel/") || lower.contains("/p/") ||
                                            lower.contains("/video/") || lower.contains("/status/") ||
                                            lower.contains("/pin/")
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

                                    // DOM video tag sniffer: detects video tags on Facebook, Instagram, Pinterest, TikTok
                                    view?.evaluateJavascript(
                                        """
                                        (function() {
                                            var vids = document.querySelectorAll('video');
                                            for (var i = 0; i < vids.length; i++) {
                                                var src = vids[i].currentSrc || vids[i].src;
                                                if (src && src.indexOf('http') === 0 && src.indexOf('blob:') !== 0) {
                                                    console.log('VIDFLOW_VIDEO:' + src);
                                                }
                                            }
                                        })();
                                        """.trimIndent(),
                                        null
                                    )
                                }

                                override fun doUpdateVisitedHistory(view: WebView?, url: String?, isReload: Boolean) {
                                    super.doUpdateVisitedHistory(view, url, isReload)
                                    if (url != null) {
                                        currentBrowserUrl = url
                                        val title = view?.title
                                        if (!title.isNullOrBlank()) currentBrowserTitle = title
                                        val lower = url.lowercase()
                                        if (lower.contains("watch?v=") || lower.contains("/shorts/") ||
                                            lower.contains("/reel/") || lower.contains("/p/") ||
                                            lower.contains("/video/") || lower.contains("/status/") ||
                                            lower.contains("/pin/") || lower.contains("fb.watch") ||
                                            lower.contains("story_fbid")
                                        ) {
                                            detectedVideoOnPage = url
                                        }
                                        if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
                                            if (lower.contains("watch?v=") || lower.contains("/shorts/")) {
                                                viewModel.onYoutubeBrowserNavigated(url)
                                            }
                                        }
                                    }
                                }

                                override fun onReceivedSslError(
                                    view: WebView?,
                                    handler: SslErrorHandler?,
                                    error: SslError?
                                ) {
                                    handler?.proceed()
                                }

                                override fun shouldOverrideUrlLoading(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): Boolean {
                                    val uri = request.url ?: return false
                                    val urlStr = uri.toString()
                                    val scheme = uri.scheme?.lowercase() ?: ""
                                    val host = uri.host?.lowercase() ?: ""

                                    // Block market / play store redirects
                                    if (scheme == "market" || host.contains("play.google.com") || urlStr.contains("play.google.com")) {
                                        return true
                                    }

                                    // The in-app browser is dedicated exclusively to YouTube.
                                    // Social platforms (Facebook, Instagram, TikTok, Pinterest, Dailymotion)
                                    // are download-only. If any link points to them, send to downloader and stay on YouTube!
                                    val isSocialPlatform = host.contains("facebook.com") || host.contains("instagram.com") ||
                                        host.contains("tiktok.com") || host.contains("pinterest.com") ||
                                        host.contains("dailymotion.com")

                                    if (isSocialPlatform) {
                                        viewModel.onUrlChanged(urlStr)
                                        viewModel.fetchInfo()
                                        return true
                                    }

                                    if (scheme == "http" || scheme == "https") {
                                        return false // Allow normal YouTube browsing inside WebView
                                    }

                                    // Intent schemes: do NOT kick user out to external apps.
                                    if (scheme == "intent" || urlStr.startsWith("intent://")) {
                                        try {
                                            val parsedIntent = Intent.parseUri(urlStr, Intent.URI_INTENT_SCHEME)
                                            val fallback = parsedIntent.getStringExtra("browser_fallback_url")
                                                ?: parsedIntent.dataString?.takeIf { it.startsWith("http") }
                                            if (!fallback.isNullOrBlank()) {
                                                val fallbackHost = Uri.parse(fallback).host?.lowercase() ?: ""
                                                if (!fallbackHost.contains("facebook.com") && !fallbackHost.contains("instagram.com") &&
                                                    !fallbackHost.contains("tiktok.com")) {
                                                    currentBrowserUrl = fallback
                                                    view.loadUrl(fallback)
                                                }
                                                return true
                                            }
                                        } catch (_: Exception) {}
                                        return true
                                    }

                                    // Custom app schemes (e.g. instagram://, fb://, tiktok://, snssdk1233://, pinterest://):
                                    // Never launch external app!
                                    return true
                                }

                                override fun shouldInterceptRequest(
                                    view: WebView,
                                    request: WebResourceRequest,
                                ): WebResourceResponse? {
                                    val reqUrl = request.url?.toString() ?: ""
                                    val lower = reqUrl.lowercase()
                                    if (lower.contains(".mp4") || lower.contains(".m3u8") ||
                                        (lower.contains("fbcdn.net") && (lower.contains("/v/") || lower.contains("video"))) ||
                                        (lower.contains("cdninstagram.com") && (lower.contains(".mp4") || lower.contains("video"))) ||
                                        ((lower.contains("tiktokcdn.com") || lower.contains("byteoversea.com") || lower.contains("ibytedtos.com")) && (lower.contains(".mp4") || lower.contains("video"))) ||
                                        (lower.contains("v.pinimg.com") && lower.contains(".mp4")) ||
                                        (lower.contains("googlevideo.com/videoplayback") && lower.contains("&range="))
                                    ) {
                                        if (!lower.contains("preview") && !lower.contains("150x150") && !lower.contains("dash-init")) {
                                            interceptedMediaUrl = reqUrl
                                        }
                                    }
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
                            val inputUrl = state.urlInput.trim()
                            if (inputUrl.isNotBlank()) {
                                if (state.isLoading) {
                                    Toast.makeText(context, "Analyzing video, please wait...", Toast.LENGTH_SHORT).show()
                                    return@clickable
                                }
                                viewModel.fetchInfo()
                                return@clickable
                            }

                            val liveUrl = webViewRef?.url?.takeIf { it.isNotBlank() } ?: currentBrowserUrl
                            val lower = liveUrl.lowercase()

                            // If already analyzed for this URL, open dialog immediately
                            val activeInfo = state.videoInfo ?: fallbackVideoInfo
                            if (activeInfo != null && (activeInfo.url == liveUrl || liveUrl.contains(activeInfo.url) || activeInfo.url.contains(liveUrl))) {
                                showQualityPickerDialog = true
                                return@clickable
                            }

                            if (state.isLoading) {
                                Toast.makeText(context, "Analyzing video, please wait...", Toast.LENGTH_SHORT).show()
                                return@clickable
                            }

                            // If direct video stream was intercepted by WebView (for in-app YouTube browser):
                            if (!interceptedMediaUrl.isNullOrBlank() && (lower.contains("youtube.com") || lower.contains("youtu.be"))) {
                                fallbackVideoInfo = VideoInfo(
                                    url = liveUrl,
                                    title = currentBrowserTitle.ifBlank { "YouTube Video" },
                                    thumbnail = null,
                                    duration = null,
                                    uploader = null,
                                    platform = Platform.YOUTUBE,
                                    formats = listOf(
                                        VideoFormat(
                                            formatId = "direct_hd_mp4",
                                            quality = "HD Video (MP4)",
                                            ext = "mp4",
                                            filesize = null,
                                            fps = 30,
                                            vcodec = "h264",
                                            acodec = "aac",
                                            hasVideo = true,
                                            hasAudio = true,
                                            downloadUrl = interceptedMediaUrl!!,
                                        ),
                                        VideoFormat(
                                            formatId = "direct_mp3",
                                            quality = "Audio Only (MP3)",
                                            ext = "mp3",
                                            filesize = null,
                                            fps = null,
                                            vcodec = null,
                                            acodec = "mp3",
                                            hasVideo = false,
                                            hasAudio = true,
                                            downloadUrl = interceptedMediaUrl!!,
                                        )
                                    ),
                                )
                                showQualityPickerDialog = true
                                return@clickable
                            }

                            // Trigger backend analysis for browser YouTube page
                            if (lower.contains("youtube.com") || lower.contains("youtu.be")) {
                                viewModel.onUrlChanged(liveUrl)
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
                                if (detectedVideoOnPage != null || interceptedMediaUrl != null) "Download Video (Ready)" else "Download Video / Audio",
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
    val infoToPick = state.videoInfo ?: fallbackVideoInfo
    if (showQualityPickerDialog && infoToPick != null) {
        QualityPickerDialog(
            videoInfo = infoToPick,
            selectedFormat = state.selectedFormat ?: infoToPick.formats.firstOrNull(),
            onFormatSelected = viewModel::onFormatSelected,
            onStartDownload = {
                val fmtToUse = state.selectedFormat ?: infoToPick.formats.firstOrNull()
                if (fmtToUse != null) {
                    if (fallbackVideoInfo != null && state.videoInfo == null) {
                        DownloadService.start(
                            context.applicationContext as android.app.Application,
                            com.vidflow.app.service.DownloadRequest(
                                info = infoToPick,
                                format = fmtToUse,
                                downloadUrl = fmtToUse.downloadUrl
                            )
                        )
                        Toast.makeText(context, "Download started! See progress in My Files.", Toast.LENGTH_SHORT).show()
                    } else {
                        viewModel.startDownload(fmtToUse)
                    }
                }
                showQualityPickerDialog = false
                fallbackVideoInfo = null
            },
            onDismiss = {
                showQualityPickerDialog = false
                fallbackVideoInfo = null
            }
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

                val distinctFormats = remember(videoInfo.formats) {
                    videoInfo.formats.distinctBy { it.quality }
                }
                val videoFormats = remember(distinctFormats) {
                    distinctFormats.filter { it.hasVideo }
                }
                val audioFormats = remember(distinctFormats) {
                    distinctFormats.filter { !it.hasVideo && it.hasAudio }
                }

                LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                    if (videoFormats.isNotEmpty()) {
                        item {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Rounded.Videocam, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Video Options",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }
                        items(videoFormats, key = { it.formatId + it.quality }) { format ->
                            val isSelected = selectedFormat?.formatId == format.formatId ||
                                    (selectedFormat == null && format == videoFormats.firstOrNull())
                            Surface(
                                color = if (isSelected) AccentGlow else SurfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Accent) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { onFormatSelected(format) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                        FormatLabels.sizeLabel(format, videoInfo.duration),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) Accent else TextMuted
                                    )
                                }
                            }
                        }
                    }

                    if (audioFormats.isNotEmpty()) {
                        item {
                            Spacer(Modifier.height(8.dp))
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(vertical = 4.dp)
                            ) {
                                Icon(Icons.Rounded.Audiotrack, contentDescription = null, tint = Accent, modifier = Modifier.size(18.dp))
                                Spacer(Modifier.width(6.dp))
                                Text(
                                    "Audio Options (MP3)",
                                    style = MaterialTheme.typography.titleSmall,
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            }
                        }
                        items(audioFormats, key = { it.formatId + it.quality }) { format ->
                            val isSelected = selectedFormat?.formatId == format.formatId
                            Surface(
                                color = if (isSelected) AccentGlow else SurfaceVariant,
                                shape = RoundedCornerShape(12.dp),
                                border = if (isSelected) androidx.compose.foundation.BorderStroke(1.dp, Accent) else null,
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 3.dp)
                                    .clickable { onFormatSelected(format) }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
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
                                        FormatLabels.sizeLabel(format, videoInfo.duration),
                                        style = MaterialTheme.typography.labelSmall,
                                        color = if (isSelected) Accent else TextMuted
                                    )
                                }
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
