package com.vidflow.app.ui.settings

import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidflow.app.data.FileManagerHelper
import com.vidflow.app.theme.*

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onOpenLogin: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val prefs = remember { context.getSharedPreferences("vidflow_settings", Context.MODE_PRIVATE) }

    var wifiOnly by remember { mutableStateOf(prefs.getBoolean("wifi_only", false)) }
    var language by remember { mutableStateOf(prefs.getString("app_language", "English") ?: "English") }
    var rememberedFileManager by remember { mutableStateOf(FileManagerHelper.getRememberedPackage(context)) }

    var showLanguageDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        "Settings",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Background)
            )
        },
        containerColor = Background,
        modifier = modifier.fillMaxSize()
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentPadding = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            item {
                SectionHeader("Download Preferences")
                Surface(
                    color = Surface,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SettingsRow(
                            icon = Icons.Rounded.Folder,
                            title = "Download Location",
                            subtitle = "Downloads/VidFlow (Internal Storage)",
                            onClick = {
                                Toast.makeText(context, "Saved to standard Downloads/VidFlow directory", Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider(color = Outline, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsSwitchRow(
                            icon = Icons.Rounded.Wifi,
                            title = "Download over Wi-Fi Only",
                            subtitle = "Prevent downloads on mobile data",
                            checked = wifiOnly,
                            onCheckedChange = {
                                wifiOnly = it
                                prefs.edit().putBoolean("wifi_only", it).apply()
                            }
                        )
                    }
                }
            }

            item {
                SectionHeader("App & Storage Settings")
                Surface(
                    color = Surface,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        val fileAppSubtitle = when {
                            FileManagerHelper.isSamsungMyFilesInstalled(context) -> "Samsung My Files (Auto-detected)"
                            rememberedFileManager != null -> "Remembered: $rememberedFileManager"
                            else -> "Prompt each time (System chooser)"
                        }
                        SettingsRow(
                            icon = Icons.Rounded.FolderShared,
                            title = "File Manager Preference",
                            subtitle = fileAppSubtitle,
                            onClick = {
                                FileManagerHelper.resetRememberedChoice(context)
                                rememberedFileManager = null
                                Toast.makeText(context, "Remembered file manager app reset. You will be asked next time.", Toast.LENGTH_SHORT).show()
                            }
                        )
                        HorizontalDivider(color = Outline, thickness = 0.5.dp, modifier = Modifier.padding(horizontal = 16.dp))
                        SettingsRow(
                            icon = Icons.Rounded.Language,
                            title = "Language",
                            subtitle = language,
                            onClick = { showLanguageDialog = true }
                        )
                    }
                }
            }

            item {
                SectionHeader("About")
                Surface(
                    color = Surface,
                    shape = RoundedCornerShape(18.dp),
                    tonalElevation = 2.dp,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column {
                        SettingsRow(
                            icon = Icons.Rounded.Info,
                            title = "About VidFlow",
                            subtitle = "v1.2.0 (Official Build)",
                            onClick = { showAboutDialog = true }
                        )
                    }
                }
            }
        }
    }

    // Language Picker Dialog
    if (showLanguageDialog) {
        val languages = listOf("English", "Urdu (اردو)", "Hindi (हिंदी)", "Spanish (Español)", "Arabic (العربية)")
        AlertDialog(
            onDismissRequest = { showLanguageDialog = false },
            title = { Text("Select Language") },
            text = {
                Column {
                    languages.forEach { lang ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    language = lang
                                    prefs.edit().putString("app_language", lang).apply()
                                    showLanguageDialog = false
                                    Toast.makeText(context, "Language set to $lang", Toast.LENGTH_SHORT).show()
                                }
                                .padding(vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            RadioButton(selected = language == lang, onClick = null)
                            Spacer(Modifier.width(8.dp))
                            Text(lang, color = TextPrimary)
                        }
                    }
                }
            },
            confirmButton = {},
            dismissButton = {
                TextButton(onClick = { showLanguageDialog = false }) { Text("Cancel") }
            }
        )
    }

    // About Dialog
    if (showAboutDialog) {
        AlertDialog(
            onDismissRequest = { showAboutDialog = false },
            title = { Text("About VidFlow") },
            text = {
                Column {
                    Text("VidFlow v1.2.0", fontWeight = FontWeight.Bold, color = Accent)
                    Spacer(Modifier.height(4.dp))
                    Text("Package: com.vidflow.app", color = TextSecondary, fontSize = 12.sp)
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "VidFlow is a powerful 100% on-device multimedia downloader and player, powered by native yt-dlp & FFmpeg. No servers, zero data tracking, maximum privacy.",
                        color = TextPrimary,
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = { showAboutDialog = false }) { Text("OK") }
            }
        )
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.labelMedium,
        color = Accent,
        fontWeight = FontWeight.Bold,
        modifier = Modifier.padding(horizontal = 4.dp, vertical = 4.dp)
    )
}

@Composable
private fun SettingsRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Icon(Icons.Rounded.ChevronRight, contentDescription = null, tint = TextMuted)
    }
}

@Composable
private fun SettingsSwitchRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(icon, contentDescription = null, tint = Accent, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(16.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold, color = TextPrimary)
            Spacer(Modifier.height(2.dp))
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = TextSecondary)
        }
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = SwitchDefaults.colors(checkedThumbColor = Background, checkedTrackColor = Accent)
        )
    }
}
