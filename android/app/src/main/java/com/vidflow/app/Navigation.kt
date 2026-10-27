package com.vidflow.app

import android.os.Environment
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.vidflow.app.data.FileManagerHelper
import com.vidflow.app.theme.*
import com.vidflow.app.ui.files.MyFilesScreen
import com.vidflow.app.ui.history.HistoryViewModel
import com.vidflow.app.ui.home.HomeScreen
import com.vidflow.app.ui.home.HomeViewModel
import com.vidflow.app.ui.library.LibraryScreen
import com.vidflow.app.ui.login.LoginWebViewScreen
import com.vidflow.app.ui.player.FullScreenVideoPlayerDialog
import com.vidflow.app.ui.player.MediaPlayerManager
import com.vidflow.app.ui.player.MiniPlayerBar
import com.vidflow.app.ui.search.SearchScreen
import com.vidflow.app.ui.settings.SettingsScreen
import com.vidflow.app.ui.whatsapp.WhatsAppStatusScreen

enum class MainNavTab(val label: String, val icon: ImageVector) {
    HOME("Home", Icons.Rounded.Home),
    SEARCH("Search", Icons.Rounded.Search),
    MY_FILES("My Files", Icons.Rounded.Download),
    LIBRARY("Library", Icons.Rounded.VideoLibrary),
    SETTINGS("Settings", Icons.Rounded.Settings),
}

@Composable
fun MainNavigation(
    homeVm: HomeViewModel,
    historyVm: HistoryViewModel,
) {
    val context = LocalContext.current
    var selectedTab by remember { mutableStateOf(MainNavTab.HOME) }
    var showWhatsAppSaver by remember { mutableStateOf(false) }
    var showLoginScreen by remember { mutableStateOf(false) }
    var searchQueryForSearchTab by remember { mutableStateOf("") }
    var showFullScreenPlayer by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Background,
        bottomBar = {
            Surface(
                color = Surface,
                tonalElevation = 6.dp,
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .navigationBarsPadding()
                ) {
                    // Persistent Mini-Player Bar (Music & Video)
                    MiniPlayerBar(
                        onExpand = { showFullScreenPlayer = true }
                    )
                    HorizontalDivider(thickness = 0.5.dp, color = Outline.copy(alpha = 0.5f))
                    VidFlowBottomBar(
                        selectedTab = selectedTab,
                        onTabSelected = { tab ->
                            showWhatsAppSaver = false
                            showLoginScreen = false
                            selectedTab = tab
                        },
                    )
                }
            }
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            when {
                showWhatsAppSaver -> {
                    WhatsAppStatusScreen(
                        onBack = { showWhatsAppSaver = false }
                    )
                }
                showLoginScreen -> {
                    LoginWebViewScreen(
                        onLoginSuccess = {
                            showLoginScreen = false
                            selectedTab = MainNavTab.HOME
                        }
                    )
                }
                else -> {
                    when (selectedTab) {
                        MainNavTab.HOME -> HomeScreen(
                            viewModel = homeVm,
                            onOpenLogin = { showLoginScreen = true },
                            onOpenWhatsAppSaver = { showWhatsAppSaver = true },
                            onNavigateToSearch = { query ->
                                searchQueryForSearchTab = query
                                selectedTab = MainNavTab.SEARCH
                            }
                        )
                        MainNavTab.SEARCH -> SearchScreen(
                            initialQuery = searchQueryForSearchTab,
                            onOpenVideoInBrowser = { url ->
                                homeVm.onUrlChanged(url)
                                selectedTab = MainNavTab.HOME
                            },
                            onDownloadVideo = { url ->
                                homeVm.onUrlChanged(url)
                                homeVm.fetchInfo()
                                selectedTab = MainNavTab.HOME
                            }
                        )
                        MainNavTab.MY_FILES -> MyFilesScreen(
                            historyVm = historyVm,
                            onOpenPlayer = { uri, title, isVideo ->
                                MediaPlayerManager.play(context, uri, title, isVideo)
                                if (isVideo) showFullScreenPlayer = true
                            }
                        )
                        MainNavTab.LIBRARY -> LibraryScreen(
                            historyVm = historyVm,
                            onPlayMedia = { uri, title, isVideo ->
                                MediaPlayerManager.play(context, uri, title, isVideo)
                                if (isVideo) showFullScreenPlayer = true
                            }
                        )
                        MainNavTab.SETTINGS -> SettingsScreen(
                            onOpenLogin = { showLoginScreen = true }
                        )
                    }
                }
            }
        }
    }

    if (showFullScreenPlayer) {
        FullScreenVideoPlayerDialog(
            onDismiss = { showFullScreenPlayer = false }
        )
    }
}

@Composable
private fun VidFlowBottomBar(
    selectedTab: MainNavTab,
    onTabSelected: (MainNavTab) -> Unit,
) {
    NavigationBar(
        containerColor = Surface,
        contentColor = TextSecondary,
        tonalElevation = 0.dp,
        windowInsets = WindowInsets(0, 0, 0, 0),
        modifier = Modifier.fillMaxWidth().height(60.dp)
    ) {
        MainNavTab.entries.forEach { tab ->
            val selected = tab == selectedTab
            NavigationBarItem(
                selected = selected,
                onClick = { onTabSelected(tab) },
                icon = {
                    Icon(
                        tab.icon,
                        contentDescription = tab.label,
                        modifier = Modifier.size(22.dp),
                    )
                },
                label = {
                    Text(
                        tab.label,
                        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
                    )
                },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = Accent,
                    selectedTextColor = Accent,
                    unselectedIconColor = TextMuted,
                    unselectedTextColor = TextMuted,
                    indicatorColor = AccentGlow,
                ),
            )
        }
    }
}
