/*
*/
package moe.koiverse.imagination.ui.player

import android.os.Build
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import moe.koiverse.imagination.LocalPlayerConnection
import moe.koiverse.imagination.constants.MiniPlayerHeight
import moe.koiverse.imagination.constants.SwipeSensitivityKey
import moe.koiverse.imagination.utils.rememberPreference
import kotlin.math.roundToInt

@Composable
fun GlassMiniPlayer(
    position: Long,
    duration: Long,
    modifier: Modifier = Modifier,
    pureBlack: Boolean,
) {
    val playerConnection = LocalPlayerConnection.current ?: return
    val layoutDirection = LocalLayoutDirection.current
    val coroutineScope = rememberCoroutineScope()
    val swipeSensitivity by rememberPreference(SwipeSensitivityKey, 0.73f)
    val swipeThumbnail by rememberPreference(moe.koiverse.imagination.constants.SwipeThumbnailKey, true)

    val isDark = pureBlack || MaterialTheme.colorScheme.background.luminance() < 0.5f
    val pillShape = RoundedCornerShape(32.dp)

    // ── Budget-device glass simulation ───────────────────────────────
    // Replaces broken RenderEffect blur with layered translucent paint.
    // Looks great on Realme / devices where blur pipeline is broken.
    val baseAlpha      = if (isDark) 0.72f else 0.78f
    val gradientAlpha  = if (isDark) 0.18f else 0.12f
    val borderAlpha    = if (isDark) 0.28f else 0.22f

    val baseColor = if (pureBlack)
        Color(0xFF000000).copy(alpha = baseAlpha)
    else if (isDark)
        Color(0xFF1C1C2E).copy(alpha = baseAlpha)   // deep navy-dark
    else
        Color(0xFFF5F5F7).copy(alpha = baseAlpha)   // light frosted

    val gradientTop = if (isDark)
        Color(0xFFFFFFFF).copy(alpha = gradientAlpha)
    else
        Color(0xFF000000).copy(alpha = gradientAlpha * 0.5f)

    val borderColor = if (isDark)
        Color.White.copy(alpha = borderAlpha)
    else
        Color.Black.copy(alpha = borderAlpha * 0.6f)

    SwipeableMiniPlayerBox(
        modifier = modifier,
        swipeSensitivity = swipeSensitivity,
        swipeThumbnail = swipeThumbnail,
        playerConnection = playerConnection,
        layoutDirection = layoutDirection,
        coroutineScope = coroutineScope,
        pureBlack = pureBlack,
        useLegacyBackground = false
    ) { offsetX ->
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .height(MiniPlayerHeight)
                .offset { IntOffset(offsetX.roundToInt(), 0) }
                .clip(pillShape)
                // ✅ Layered translucent glass look — no blur needed
                .drawBehind {
                    // Layer 1: solid translucent base
                    drawRect(color = baseColor)
                    // Layer 2: subtle top-to-bottom gradient shimmer
                    drawRect(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                gradientTop,
                                Color.Transparent
                            )
                        )
                    )
                }
                .border(
                    width = 0.5.dp,
                    brush = Brush.verticalGradient(
                        colors = listOf(
                            borderColor,
                            borderColor.copy(alpha = borderAlpha * 0.3f),
                        )
                    ),
                    shape = pillShape
                )
        ) {
            NewMiniPlayerContent(
                pureBlack = pureBlack,
                position = position,
                duration = duration,
                playerConnection = playerConnection
            )
        }
    }
}

