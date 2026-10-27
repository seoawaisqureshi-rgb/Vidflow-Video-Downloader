package com.vidflow.app

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        // If the previous launch crashed, show the saved crash report instead
        // of the normal UI so the error can be seen and shared.
        val crashReport = readCrashReport(application)
        if (crashReport != null) {
            setContent {
                VidFlowTheme {
                    CrashReportScreen(
                        report = crashReport,
                        onClear = {
                            clearCrashReport(application)
                            recreate()
                        },
                    )
                }
            }
            return
        }
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

@Composable
private fun CrashReportScreen(report: String, onClear: () -> Unit) {
    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
        ) {
            Text("VidFlow crashed on last launch", style = MaterialTheme.typography.titleMedium)
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "Below is the crash report. Please share it so the crash can be fixed.",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(report, style = MaterialTheme.typography.bodySmall)
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onClear) {
                Text("Clear and continue")
            }
        }
    }
}
