package com.vidflow.app

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.vidflow.app.theme.*
import com.vidflow.app.ui.history.HistoryScreen
import com.vidflow.app.ui.history.HistoryViewModel
import com.vidflow.app.ui.home.HomeScreen
import com.vidflow.app.ui.home.HomeViewModel
import java.io.File

private enum class NavTab(val label: String, val icon: ImageVector) {
    HOME("Download", Icons.Rounded.Download),
    FOLDER("Folder", Icons.Rounded.FolderOpen),
    HISTORY("History", Icons.Rounded.History),
}

@Composable
fun MainNavigation(
    homeVm: HomeViewModel,
    historyVm: HistoryViewModel,
) {
    var selectedTab by remember { mutableStateOf(NavTab.HOME) }
    val context = LocalContext.current

    Scaffold(
        containerColor = Background,
        bottomBar = {
            VidFlowBottomBar(
                selectedTab = selectedTab,
                onTabSelected = { tab ->
                    if (tab == NavTab.FOLDER) {
                        // Open Downloads/VidFlow folder — don't change tab
                        openDownloadsFolder(context)
                    } else {
                        selectedTab = tab
                    }
                },
            )
        },
    ) { innerPadding ->
        when (selectedTab) {
            NavTab.HOME    -> HomeScreen(viewModel = homeVm, modifier = Modifier.padding(innerPadding))
            NavTab.HISTORY -> HistoryScreen(viewModel = historyVm, modifier = Modifier.padding(innerPadding))
            NavTab.FOLDER  -> HomeScreen(viewModel = homeVm, modifier = Modifier.padding(innerPadding))
        }
    }
}

@Composable
private fun VidFlowBottomBar(
    selectedTab: NavTab,
    onTabSelected: (NavTab) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Background),
    ) {
        HorizontalDivider(thickness = 1.dp, color = Outline)
        NavigationBar(
            containerColor = Surface.copy(alpha = 0.95f),
            contentColor = TextSecondary,
            tonalElevation = 0.dp,
        ) {
            NavTab.entries.forEach { tab ->
                val selected = tab == selectedTab
                NavigationBarItem(
                    selected = selected && tab != NavTab.FOLDER,
                    onClick = { onTabSelected(tab) },
                    icon = {
                        Icon(
                            tab.icon,
                            contentDescription = tab.label,
                            modifier = Modifier.size(22.dp),
                        )
                    },
                    label = { Text(tab.label) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor   = Violet,
                        selectedTextColor   = Violet,
                        unselectedIconColor = if (tab == NavTab.FOLDER) Cyan else TextMuted,
                        unselectedTextColor = if (tab == NavTab.FOLDER) Cyan else TextMuted,
                        indicatorColor      = VioletGlow,
                    ),
                )
            }
        }
    }
}

/** Opens Downloads/VidFlow/ folder in the system file manager. */
private fun openDownloadsFolder(context: android.content.Context) {
    // Approach 1: DocumentsUI deep-link to VidFlow folder
    try {
        val uri = android.net.Uri.parse(
            "content://com.android.externalstorage.documents/document/primary%3ADownload%2FVidFlow"
        )
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "vnd.android.document/directory")
            addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(intent)
        return
    } catch (_: Exception) {}

    // Approach 2: System Downloads app
    try {
        val intent = android.content.Intent(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS)
        context.startActivity(intent)
        return
    } catch (_: Exception) {}

    // Approach 3: Generic Files app
    try {
        val intent = android.content.Intent(android.content.Intent.ACTION_VIEW).apply {
            setDataAndType(
                android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                "vnd.android.document/directory",
            )
        }
        context.startActivity(intent)
    } catch (_: Exception) {
        android.widget.Toast.makeText(
            context, "Could not open file manager. Check Downloads/VidFlow/ manually.", android.widget.Toast.LENGTH_LONG
        ).show()
    }
}
