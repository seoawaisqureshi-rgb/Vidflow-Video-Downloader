package com.vidflow.app.ui.components

import androidx.compose.animation.core.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.vidflow.app.theme.*

/** Glassmorphism card surface */
@Composable
fun GlassCard(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 16.dp,
    content: @Composable ColumnScope.() -> Unit,
) {
    Column(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(SurfaceGlass)
            .border(
                width = 1.dp,
                brush = Brush.linearGradient(listOf(
                    Color.White.copy(alpha = 0.25f),
                    Color.White.copy(alpha = 0.05f),
                )),
                shape = RoundedCornerShape(cornerRadius),
            )
            .padding(16.dp),
        content = content,
    )
}

/** Gradient accent button (Violet → Cyan) */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    icon: @Composable (() -> Unit)? = null,
) {
    val gradient = Brush.linearGradient(
        colors = if (enabled) listOf(Violet, Cyan) else listOf(TextMuted, TextMuted),
        start = Offset(0f, 0f),
        end = Offset(Float.POSITIVE_INFINITY, 0f),
    )
    Button(
        onClick = onClick,
        enabled = enabled,
        modifier = modifier.height(52.dp),
        shape = RoundedCornerShape(14.dp),
        colors = ButtonDefaults.buttonColors(
            containerColor = Color.Transparent,
            contentColor = TextPrimary,
            disabledContainerColor = Color.Transparent,
            disabledContentColor = TextMuted,
        ),
        contentPadding = PaddingValues(0.dp),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(gradient, RoundedCornerShape(14.dp)),
            contentAlignment = Alignment.Center,
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                icon?.invoke()
                Text(text, fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
            }
        }
    }
}

/** Animated shimmer loading indicator */
@Composable
fun ShimmerBox(
    modifier: Modifier = Modifier,
    cornerRadius: Dp = 8.dp,
) {
    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateX by transition.animateFloat(
        initialValue = -300f,
        targetValue = 900f,
        animationSpec = infiniteRepeatable(
            animation = tween(1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_x",
    )
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(cornerRadius))
            .background(
                Brush.linearGradient(
                    colors = listOf(
                        SurfaceVariant,
                        Color.White.copy(alpha = 0.08f),
                        SurfaceVariant,
                    ),
                    start = Offset(translateX, 0f),
                    end = Offset(translateX + 300f, 300f),
                )
            )
    )
}

/** Platform badge chip */
@Composable
fun PlatformBadge(name: String, modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .clip(RoundedCornerShape(20.dp))
            .background(VioletGlow)
            .border(1.dp, Violet.copy(alpha = 0.4f), RoundedCornerShape(20.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp),
    ) {
        Text(
            text = name,
            style = MaterialTheme.typography.labelSmall,
            color = Violet,
            fontWeight = FontWeight.SemiBold,
        )
    }
}

/** Download progress bar */
@Composable
fun DownloadProgressBar(
    progress: Float,   // 0f–1f, negative for indeterminate
    modifier: Modifier = Modifier,
) {
    val animatedProgress by animateFloatAsState(
        targetValue = if (progress < 0f) 0f else progress,
        animationSpec = tween(300),
        label = "progress",
    )
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(SurfaceVariant),
    ) {
        if (progress < 0f) {
            // Indeterminate — sliding animation
            val slide = rememberInfiniteTransition(label = "indeterminate").animateFloat(
                initialValue = -0.4f, targetValue = 1.0f,
                animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)),
                label = "slide",
            )
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.35f)
                    .offset(x = (slide.value * 350).dp)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(Violet, Cyan))),
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(animatedProgress)
                    .clip(RoundedCornerShape(3.dp))
                    .background(Brush.horizontalGradient(listOf(Violet, Cyan))),
            )
        }
    }
}
