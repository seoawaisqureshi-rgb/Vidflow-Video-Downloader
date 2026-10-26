package com.vidflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vidflow.app.theme.VidFlowTheme
import com.vidflow.app.ui.history.HistoryViewModel
import com.vidflow.app.ui.home.HomeViewModel

class MainActivity : ComponentActivity() {

    private val homeViewModel: HomeViewModel by viewModels {
        ViewModelFactory(application, (application as VidFlowApplication).repository)
    }

    private val historyViewModel: HistoryViewModel by viewModels {
        ViewModelFactory(application, (application as VidFlowApplication).repository)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VidFlowTheme {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    MainNavigation(
                        homeVm = homeViewModel,
                        historyVm = historyViewModel,
                    )
                }
            }
        }

        // Handle shared URL from other apps (Share menu)
        handleShareIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleShareIntent(intent)
    }

    private fun handleShareIntent(intent: Intent?) {
        if (intent == null) return
        val rawText = when (intent.action) {
            Intent.ACTION_SEND -> {
                intent.getStringExtra(Intent.EXTRA_TEXT)
                    ?: intent.getCharSequenceExtra(Intent.EXTRA_TEXT)?.toString()
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.text?.toString()
                    ?: intent.clipData?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.uri?.toString()
            }
            Intent.ACTION_VIEW -> intent.dataString
            else -> null
        } ?: return

        // Extract HTTP/HTTPS URL from any shared text (handles extra platform titles, e.g. Facebook / Instagram share)
        val urlRegex = Regex("""https?://[^\s"'<>]+""")
        val match = urlRegex.find(rawText) ?: return
        val cleanUrl = match.value.trim().trimEnd('.', ',', '!', ';', ')')

        // Automatically populate the input field and trigger analysis
        homeViewModel.onUrlChanged(cleanUrl)
        homeViewModel.fetchInfo()
    }
}
