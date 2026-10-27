package com.vidflow.app.ui.player

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.vidflow.app.theme.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class PlaybackState(
    val uri: String? = null,
    val title: String = "",
    val isVideo: Boolean = true,
    val isPlaying: Boolean = false,
    val isVisible: Boolean = false,
)

object MediaPlayerManager {
    private var exoPlayer: ExoPlayer? = null
    private val _playbackState = MutableStateFlow(PlaybackState())
    val playbackState = _playbackState.asStateFlow()

    fun getPlayer(context: Context): ExoPlayer {
        return exoPlayer ?: ExoPlayer.Builder(context.applicationContext).build().also { player ->
            exoPlayer = player
            player.addListener(object : Player.Listener {
                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playbackState.value = _playbackState.value.copy(isPlaying = isPlaying)
                }
                override fun onPlaybackStateChanged(state: Int) {
                    if (state == Player.STATE_ENDED) {
                        _playbackState.value = _playbackState.value.copy(isPlaying = false)
                    }
                }
            })
        }
    }

    fun play(context: Context, uri: String, title: String, isVideo: Boolean = true) {
        val player = getPlayer(context)
        val mediaUri = if (uri.startsWith("/") || uri.startsWith("content://") || uri.startsWith("http")) {
            Uri.parse(uri)
        } else {
            Uri.parse("file://$uri")
        }
        val mediaItem = MediaItem.fromUri(mediaUri)
        player.setMediaItem(mediaItem)
        player.prepare()
        player.play()
        _playbackState.value = PlaybackState(
            uri = uri,
            title = title,
            isVideo = isVideo,
            isPlaying = true,
            isVisible = true,
        )
    }

    fun togglePlayPause() {
        exoPlayer?.let { player ->
            if (player.isPlaying) player.pause() else player.play()
        }
    }

    fun stop() {
        exoPlayer?.stop()
        _playbackState.value = PlaybackState(isVisible = false, isPlaying = false)
    }

    fun release() {
        exoPlayer?.release()
        exoPlayer = null
        _playbackState.value = PlaybackState(isVisible = false, isPlaying = false)
    }
}

/**
 * Pinned Mini-Player bar that floats right above the Bottom Navigation Bar
 * whenever music or video is playing.
 */
@Composable
fun MiniPlayerBar(
    onExpand: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val state by MediaPlayerManager.playbackState.collectAsState()
    if (!state.isVisible) return

    Surface(
        color = SurfaceVariant,
        tonalElevation = 6.dp,
        shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onExpand)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (state.isVideo) AccentDeep else Accent),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    if (state.isVideo) Icons.Rounded.Movie else Icons.Rounded.MusicNote,
                    contentDescription = null,
                    tint = if (state.isVideo) Color.White else Background,
                    modifier = Modifier.size(20.dp)
                )
            }

            Spacer(Modifier.width(12.dp))

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = state.title.ifEmpty { "Playing media" },
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = TextPrimary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Text(
                    text = if (state.isVideo) "Video Player" else "Music Player",
                    style = MaterialTheme.typography.labelSmall,
                    color = Accent,
                )
            }

            IconButton(onClick = { MediaPlayerManager.togglePlayPause() }) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.PauseCircleFilled else Icons.Rounded.PlayCircleFilled,
                    contentDescription = "Play/Pause",
                    tint = Accent,
                    modifier = Modifier.size(36.dp)
                )
            }

            IconButton(onClick = { MediaPlayerManager.stop() }) {
                Icon(
                    Icons.Rounded.Close,
                    contentDescription = "Close",
                    tint = TextSecondary,
                    modifier = Modifier.size(22.dp)
                )
            }
        }
    }
}

/**
 * Fullscreen or Dialog Video Player
 */
@OptIn(UnstableApi::class)
@Composable
fun FullScreenVideoPlayerDialog(
    onDismiss: () -> Unit,
) {
    val context = LocalContext.current
    val state by MediaPlayerManager.playbackState.collectAsState()
    val player = remember { MediaPlayerManager.getPlayer(context) }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            AndroidView(
                factory = { ctx ->
                    PlayerView(ctx).apply {
                        this.player = player
                        useController = true
                        setShowNextButton(false)
                        setShowPreviousButton(false)
                    }
                },
                modifier = Modifier.fillMaxSize()
            )

            // Header close button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
                    .align(Alignment.TopCenter),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = state.title,
                    color = Color.White,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(36.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.6f))
                ) {
                    Icon(Icons.Rounded.Close, contentDescription = "Close", tint = Color.White)
                }
            }
        }
    }
}
