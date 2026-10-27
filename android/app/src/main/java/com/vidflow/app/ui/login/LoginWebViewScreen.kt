package com.vidflow.app.ui.login

import android.annotation.SuppressLint
import android.webkit.CookieManager
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
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
) {
    val context = LocalContext.current
    var selected by remember {
        mutableStateOf(SessionCookies.LoginPlatform.INSTAGRAM)
    }
    var loggedIn by remember { mutableStateOf(false) }
    var webViewRef by remember { mutableStateOf<WebView?>(null) }

    fun refreshLoginState() {
        loggedIn = SessionCookies.hasSession(selected.platform)
    }

    // The banner must reflect the real session every time this tab is
    // shown again: a remembered value goes stale across logout, tab
    // switches and Browser-tab logins, and a stale "Logged in" banner is
    // worse than no banner at all.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshLoginState()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    Column(modifier = modifier.fillMaxSize().background(Background)) {
        Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 16.dp)) {
            Text(
                "Login for stories & private posts",
                style = MaterialTheme.typography.titleMedium,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.height(6.dp))
            Text(
                "Sign in inside the browser below. VidFlow never sees your " +
                    "password — it stays on the login page. After login, " +
                    "stories and login-only posts download like public ones.",
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
                            containerColor = if (isSelected) Accent else TextMuted.copy(alpha = 0.25f),
                        ),
                        shape = RoundedCornerShape(20.dp),
                    ) {
                        Text(platform.label)
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    if (loggedIn) Icons.Rounded.CheckCircle else Icons.Rounded.Lock,
                    contentDescription = null,
                    tint = if (loggedIn) Success else TextMuted,
                )
                Spacer(Modifier.width(8.dp))
                Text(
                    if (loggedIn) "Logged in to ${selected.label} — stories will work now"
                    else "Not logged in to ${selected.label}",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (loggedIn) Success else TextMuted,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f),
                )
                OutlinedButton(
                    onClick = {
                        SessionCookies.logout(context, selected.platform)
                        webViewRef?.loadUrl(selected.loginUrl)
                        // Truthful state: re-read the session instead of
                        // blindly claiming logged-out (a failed clear used
                        // to leave the user logged in with a "logged out"
                        // banner, and vice versa).
                        loggedIn = SessionCookies.hasSession(selected.platform)
                    },
                ) {
                    Icon(Icons.Rounded.Logout, contentDescription = null)
                    Spacer(Modifier.width(4.dp))
                    Text("Logout")
                }
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "After signing in, tap any video link on the Download tab — " +
                    "or browse in the Browser tab, where you are already logged in.",
                style = MaterialTheme.typography.labelSmall,
                color = TextMuted,
            )
        }

        AndroidView(
            factory = {
                WebView(it).apply {
                    settings.javaScriptEnabled = true
                    settings.domStorageEnabled = true
                    CookieManager.getInstance().setAcceptCookie(true)
                    webViewClient = object : WebViewClient() {
                        override fun onPageFinished(view: WebView, url: String) {
                            super.onPageFinished(view, url)
                            refreshLoginState()
                        }
                    }
                    webViewRef = this
                    loadUrl(selected.loginUrl)
                }
            },
            modifier = Modifier.fillMaxWidth().weight(1f),
        )
    }
}

/** Convenience: which login platform, if any, a login-required error is about. */
fun loginPlatformFor(platform: Platform): SessionCookies.LoginPlatform? =
    SessionCookies.platformFor(platform)
