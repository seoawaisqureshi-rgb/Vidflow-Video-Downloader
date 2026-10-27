package com.vidflow.app.ui.search

import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.vidflow.app.theme.*

data class SearchResultItem(
    val title: String,
    val url: String,
    val thumbnail: String,
    val duration: String,
    val platform: String,
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    initialQuery: String = "",
    onOpenVideoInBrowser: (String) -> Unit,
    onDownloadVideo: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val keyboard = LocalSoftwareKeyboardController.current
    val prefs = remember { context.getSharedPreferences("vidflow_search_prefs", Context.MODE_PRIVATE) }

    var query by remember { mutableStateOf(initialQuery) }
    var searchHistory by remember {
        mutableStateOf(
            prefs.getStringSet("history", setOf("Trending Music 2026", "Viral Reels", "Top Podcasts", "Shorts"))?.toList() ?: emptyList()
        )
    }

    val sampleTrending = listOf(
        SearchResultItem("Trending Music Video 2026 Hits", "https://www.youtube.com/results?search_query=trending+music", "https://picsum.photos/300/170?1", "3:45", "YouTube"),
        SearchResultItem("Viral Comedy Shorts & Reels", "https://www.instagram.com/reels/", "https://picsum.photos/300/170?2", "0:58", "Instagram"),
        SearchResultItem("Top Tech Reviews & AI Insights", "https://www.youtube.com/results?search_query=tech+reviews", "https://picsum.photos/300/170?3", "12:20", "YouTube"),
        SearchResultItem("Best Workout & Fitness Motivation", "https://www.tiktok.com/", "https://picsum.photos/300/170?4", "1:15", "TikTok"),
        SearchResultItem("Epic Nature & 4K Relaxation Visuals", "https://www.youtube.com/results?search_query=4k+nature", "https://picsum.photos/300/170?5", "8:10", "YouTube"),
    )

    fun saveQuery(q: String) {
        val updated = (listOf(q) + searchHistory.filter { it != q }).take(10)
        searchHistory = updated
        prefs.edit().putStringSet("history", updated.toSet()).apply()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Search video and download", color = TextMuted, fontSize = 14.sp) },
                        leadingIcon = { Icon(Icons.Rounded.Search, contentDescription = null, tint = Accent) },
                        trailingIcon = {
                            if (query.isNotEmpty()) {
                                IconButton(onClick = { query = "" }) {
                                    Icon(Icons.Rounded.Close, contentDescription = "Clear", tint = TextMuted)
                                }
                            }
                        },
                        singleLine = true,
                        shape = RoundedCornerShape(24.dp),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Accent,
                            unfocusedBorderColor = Outline,
                            focusedContainerColor = Surface,
                            unfocusedContainerColor = Surface,
                        ),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(end = 8.dp)
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
            // Search History Chips
            if (searchHistory.isNotEmpty()) {
                item {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Recent Searches", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = TextSecondary)
                        TextButton(
                            onClick = {
                                searchHistory = emptyList()
                                prefs.edit().remove("history").apply()
                            }
                        ) {
                            Text("Clear", color = TextMuted, fontSize = 12.sp)
                        }
                    }
                    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(searchHistory) { tag ->
                            Surface(
                                color = SurfaceVariant,
                                shape = RoundedCornerShape(16.dp),
                                modifier = Modifier.clickable {
                                    query = tag
                                    val targetUrl = "https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(tag, "UTF-8")}"
                                    onOpenVideoInBrowser(targetUrl)
                                }
                            ) {
                                Row(
                                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Icon(Icons.Rounded.History, contentDescription = null, tint = TextMuted, modifier = Modifier.size(14.dp))
                                    Spacer(Modifier.width(6.dp))
                                    Text(tag, style = MaterialTheme.typography.bodySmall, color = TextPrimary)
                                }
                            }
                        }
                    }
                }
            }

            // Trending & Search Results Section
            item {
                Text(
                    if (query.isBlank()) "Discover & Trending Videos" else "Search Results for '$query'",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Accent
                )
            }

            items(sampleTrending) { item ->
                SearchResultCard(
                    item = item,
                    onClick = {
                        val searchTarget = if (query.isNotBlank()) {
                            "https://www.youtube.com/results?search_query=${java.net.URLEncoder.encode(query, "UTF-8")}"
                        } else item.url
                        saveQuery(if (query.isNotBlank()) query else item.title)
                        onOpenVideoInBrowser(searchTarget)
                    },
                    onDownload = {
                        saveQuery(if (query.isNotBlank()) query else item.title)
                        onDownloadVideo(item.url)
                    }
                )
            }
        }
    }
}

@Composable
private fun SearchResultCard(
    item: SearchResultItem,
    onClick: () -> Unit,
    onDownload: () -> Unit,
) {
    Surface(
        color = Surface,
        shape = RoundedCornerShape(16.dp),
        tonalElevation = 2.dp,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
    ) {
        Row(
            modifier = Modifier.padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(width = 96.dp, height = 62.dp)
                    .clip(RoundedCornerShape(10.dp))
                    .background(SurfaceVariant)
            ) {
                AsyncImage(
                    model = item.thumbnail,
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(4.dp)
                        .clip(RoundedCornerShape(4.dp))
                        .background(Color.Black.copy(alpha = 0.75f))
                        .padding(horizontal = 4.dp, vertical = 2.dp)
                ) {
                    Text(item.duration, color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    item.title,
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    item.platform,
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent
                )
            }

            IconButton(
                onClick = onDownload,
                modifier = Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(Accent)
            ) {
                Icon(
                    Icons.Rounded.Download,
                    contentDescription = "Download",
                    tint = Background,
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}
