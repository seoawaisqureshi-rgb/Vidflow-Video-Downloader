package com.vidflow.app.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.Logout
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.vidflow.app.data.model.Platform
import com.vidflow.app.data.session.SessionCookies
import com.vidflow.app.theme.Background
import com.vidflow.app.theme.Success
import com.vidflow.app.theme.TextMuted
import com.vidflow.app.theme.TextPrimary
import com.vidflow.app.theme.TextSecondary
import com.vidflow.app.theme.Accent
import com.vidflow.app.theme.Outline
import com.vidflow.app.theme.SurfaceVariant

/**
 * Login screen (brief sections 2.3 and 3.3).
 *
 * The user signs in to their OWN Instagram, Facebook or Threads account
 * inside this WebView. VidFlow has no password field and never sees or
 * stores the password — the WebView holds the session, exactly like a
 * browser. Afterwards SessionCookies reads the session and attaches it to
 * extraction and download requests, so stories and login-only posts go
 * through the same chain as public posts.
 *
 * Logout deletes the WebView cookies and the local cookies.txt file.
 */
@SuppressLint("SetJavaScriptEnabled")
@Composable
fun LoginWebViewScreen(
    modifier: Modifier = Modifier,
    onLoginSuccess: () -> Unit = {},
) {
    val context = LocalContext.current
    var selected by remember {
        mutableStateOf(SessionCookies.LoginPlatform.INSTAGRAM)
    }
    var loggedIn by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }
    var isLoadingPage by remember { mutableStateOf(false) }

    fun refreshLoginState() {
        val wasLoggedIn = loggedIn
        val isNowLoggedIn = SessionCookies.hasSession(selected.platform)
        loggedIn = isNowLoggedIn
        if (!wasLoggedIn && isNowLoggedIn) {
            onLoginSuccess()
        }
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshLoginState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 14.dp)) {
            Text(
                "Login for Stories & Private Posts",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Sign in directly below. VidFlow never sees or stores your password — it stays completely inside your local browser. Once logged in, private videos & stories will download seamlessly.",
                style = MaterialTheme.typography.bodySmall,
                color = TextSecondary,
            )
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SessionCookies.LoginPlatform.entries.forEach { platform ->
                    val isSelected = platform == selected
                    Button(
                        onClick = {
                            selected = platform
                            loggedIn = SessionCookies.hasSession(platform.platform)
                            webViewRef?.loadUrl(platform.loginUrl)
                        },
                        colors = ButtonDefaults.buttonColors(
                            containerColor = if (isSelected) Accent else SurfaceVariant,
                            contentColor = if (isSelected) Background else TextPrimary,
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(platform.label, fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (loggedIn) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = if (loggedIn) Success else TextMuted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (loggedIn) "Logged in to ${selected.label} — stories & private videos ready"
                    else "Sign in to ${selected.label} below",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (loggedIn) Success else TextSecondary,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                if (loggedIn) {
                    OutlinedButton(
                        onClick = {
                            SessionCookies.logout(context, selected.platform)
                            webViewRef?.loadUrl(selected.loginUrl)
                            loggedIn = SessionCookies.hasSession(selected.platform)
                        },
                        shape = RoundedCornerShape(16.dp),
                    ) {
                        Icon(Icons.Rounded.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                        Spacer(Modifier.width(4.dp))
                        Text("Logout", style = MaterialTheme.typography.labelSmall)
                    }
                }
            }
            if (loggedIn) {
                Spacer(Modifier.height(8.dp))
                Button(
                    onClick = onLoginSuccess,
                    colors = ButtonDefaults.buttonColors(containerColor = Accent, contentColor = Background),
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth().height(36.dp),
                ) {
                    Text("Return to Download Tab", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.labelMedium)
                }
            }
        }

        Box(modifier = Modifier.fillMaxWidth().weight(1f)) {
            AndroidView(
                factory = { ctx ->
                    WebView(ctx).apply {
                        // Bug 2 fix: Disable scrollbars that look like stray volume sliders
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
                            javaScriptCanOpenWindowsAutomatically = true
                            // Use standard mobile Chrome user agent so Instagram does not detect embedded webview & blank out
                            userAgentString = "Mozilla/5.0 (Linux; Android 14; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/128.0.0.0 Mobile Safari/537.36"
                        }

                        val cookieManager = CookieManager.getInstance()
                        cookieManager.setAcceptCookie(true)
                        cookieManager.setAcceptThirdPartyCookies(this, true)

                        webViewClient = object : WebViewClient() {
                            override fun onPageStarted(view: WebView?, url: String?, favicon: android.graphics.Bitmap?) {
                                super.onPageStarted(view, url, favicon)
                                isLoadingPage = true
                            }

                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                isLoadingPage = false
                                refreshLoginState()
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: android.webkit.WebResourceRequest
                            ): Boolean {
                                val uri = request.url ?: return false
                                val urlStr = uri.toString()
                                val scheme = uri.scheme?.lowercase() ?: ""
                                if (scheme == "intent" || urlStr.startsWith("intent://")) {
                                    try {
                                        val intent = android.content.Intent.parseUri(urlStr, android.content.Intent.URI_INTENT_SCHEME)
                                        val fallback = intent.getStringExtra("browser_fallback_url")
                                        if (!fallback.isNullOrBlank()) {
                                            view.loadUrl(fallback)
                                            return true
                                        }
                                        val data = intent.data
                                        if (data != null && (data.scheme == "http" || data.scheme == "https")) {
                                            view.loadUrl(data.toString())
                                            return true
                                        }
                                    } catch (e: Exception) {
                                        // ignore
                                    }
                                    return true
                                }
                                if (scheme != "http" && scheme != "https") {
                                    return true
                                }
                                return false
                            }
                        }
                        webViewRef = this
                        loadUrl(selected.loginUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )

            if (isLoadingPage) {
                androidx.compose.material3.LinearProgressIndicator(
                    modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter),
                    color = Accent,
                    trackColor = Outline,
                )
            }
        }
    }
}

/** Convenience: which login platform, if any, a login-required error is about. */
fun loginPlatformFor(platform: Platform): SessionCookies.LoginPlatform? =
    SessionCookies.platformFor(platform)
