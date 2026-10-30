package com.vidflow.app.ui.browser

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vidflow.app.data.extractor.DetectedVideo
import com.vidflow.app.data.extractor.WebViewDetector
import com.vidflow.app.theme.Background
import com.vidflow.app.theme.AccentDeep
import com.vidflow.app.theme.Outline
import com.vidflow.app.theme.Surface
import com.vidflow.app.theme.SurfaceVariant
import com.vidflow.app.theme.TextMuted
import com.vidflow.app.theme.TextPrimary
import com.vidflow.app.theme.TextSecondary
import com.vidflow.app.theme.Accent

private data class Shortcut(val label: String, val url: String)

/**
 * In-app browser tab (brief section 4). The user browses normally; the
 * WebViewDetector watches every network request, and the moment a video is
 * spotted a floating download button appears. Tapping it hands the URL to
 * the normal extraction chain via [onDownloadDetected].
 *
 * The browser shares the app-wide WebView cookie store with the Login tab,
 * so logging in here also logs extraction in.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun BrowserScreen(
    onDownloadDetected: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val detected = remember { mutableStateListOf<DetectedVideo>() }
    var address by remember { mutableStateOf("") }
    var currentTitle by remember { mutableStateOf("Browse to a video page") }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    val detector = remember {
        WebViewDetector { video ->
            if (detected.none { it.url == video.url }) detected.add(video)
        }
    }

    BackHandler(enabled = webViewRef?.canGoBack() == true) {
        webViewRef?.goBack()
    }

    Box(modifier = modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.fillMaxSize()) {
            // Address bar
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = { webViewRef?.goBack() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.ArrowBack, contentDescription = "Back", tint = TextSecondary)
                }
                IconButton(onClick = { webViewRef?.goForward() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.ArrowForward, contentDescription = "Forward", tint = TextSecondary)
                }
                OutlinedTextField(
                    value = address,
                    onValueChange = { address = it },
                    placeholder = { Text("Search or enter URL", color = TextMuted) },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(24.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Accent,
                        unfocusedBorderColor = Outline,
                        focusedContainerColor = Surface,
                        unfocusedContainerColor = Surface,
                        cursorColor = AccentDeep,
                    ),
                    trailingIcon = {
                        IconButton(onClick = {
                            val target = normalizeBrowserUrl(address)
                            address = target
                            webViewRef?.loadUrl(target)
                        }) {
                            Icon(Icons.Rounded.Search, contentDescription = "Go", tint = AccentDeep)
                        }
                    },
                )
                IconButton(onClick = { webViewRef?.reload() }, modifier = Modifier.size(36.dp)) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Reload", tint = TextSecondary)
                }
            }

            // YouTube navigation shortcuts
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                listOf(
                    Shortcut("YouTube Home", "https://m.youtube.com/"),
                    Shortcut("Trending", "https://m.youtube.com/feed/trending"),
                    Shortcut("Music", "https://m.youtube.com/channel/UC-9-kyTW8ZkZNDHQJ6FgpwQ"),
                ).forEach { shortcut ->
                    androidx.compose.material3.TextButton(
                        onClick = {
                            address = shortcut.url
                            detected.clear()
                            webViewRef?.loadUrl(shortcut.url)
                        },
                    ) {
                        Text(shortcut.label, color = AccentDeep, fontWeight = FontWeight.SemiBold)
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                currentTitle,
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 16.dp),
            )
            Spacer(Modifier.height(4.dp))

            AndroidView(
                factory = {
                    WebView(it).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        settings.mediaPlaybackRequiresUserGesture = false
                        CookieManager.getInstance().setAcceptCookie(true)
                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                address = url
                                detector.onPageStarted(view, url, favicon)
                            }

                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: android.webkit.WebResourceRequest,
                            ): android.webkit.WebResourceResponse? {
                                detector.shouldInterceptRequest(view, request)
                                return super.shouldInterceptRequest(view, request)
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                address = url
                                detector.onPageFinished(view, url)
                            }
                        }
                        webChromeClient = object : WebChromeClient() {
                            override fun onReceivedTitle(view: WebView, title: String) {
                                currentTitle = title
                            }
                        }
                        webViewRef = this
                        loadUrl("https://m.youtube.com/")
                    }
                },
                modifier = Modifier.fillMaxWidth().weight(1f),
            )

            // Detected videos strip
            if (detected.isNotEmpty()) {
                LazyColumn(
                    modifier = Modifier.fillMaxWidth()
                        .background(Surface)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    item {
                        Text(
                            "Video detected — tap Download to fetch qualities",
                            style = MaterialTheme.typography.labelMedium,
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                        )
                    }
                    items(detected, key = { it.url }) { video ->
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(SurfaceVariant)
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    (video.kind?.uppercase() ?: "VIDEO") + " • " +
                                        runCatching {
                                            android.net.Uri.parse(video.url).host ?: "video"
                                        }.getOrDefault("video"),
                                    style = MaterialTheme.typography.labelMedium,
                                    color = TextPrimary,
                                    fontWeight = FontWeight.SemiBold,
                                )
                                Text(
                                    "Play the video first if nothing appears",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = TextMuted,
                                )
                            }
                            Spacer(Modifier.width(8.dp))
                            Button(
                                onClick = { onDownloadDetected(video.url) },
                                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                                shape = RoundedCornerShape(10.dp),
                            ) {
                                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(4.dp))
                                Text("Download")
                            }
                        }
                    }
                }
            }
        }

        // Floating download button (brief 4.4): always visible once detected.
        AnimatedVisibility(
            visible = detected.isNotEmpty(),
            modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp),
        ) {
            Button(
                onClick = { detected.firstOrNull()?.let { onDownloadDetected(it.url) } },
                colors = ButtonDefaults.buttonColors(containerColor = Accent),
                shape = RoundedCornerShape(24.dp),
            ) {
                Icon(Icons.Rounded.Download, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp))
                Text("Download video (${detected.size})", fontWeight = FontWeight.Bold)
            }
        }
    }
}

private fun normalizeBrowserUrl(raw: String): String {
    val input = raw.trim()
    if (input.isEmpty()) return "https://www.instagram.com/"
    if (input.startsWith("http://") || input.startsWith("https://")) return input
    if (input.contains(" ") || !input.contains(".")) {
        return "https://duckduckgo.com/?q=" + java.net.URLEncoder.encode(input, "UTF-8")
    }
    return "https://$input"
}
