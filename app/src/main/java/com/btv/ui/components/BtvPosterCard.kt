package com.btv.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btv.R
import com.btv.ui.theme.BtvDimens
import com.btv.ui.theme.BtvGreenBright
import com.btv.ui.theme.BtvMotion
import com.btv.ui.theme.BtvShapes
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType

/**
 * Streaming poster: the artwork edge to edge (2:3, 10dp corners, no frame
 * at rest), the title and a quiet metadata line underneath. Focus lifts the
 * poster (scale + soft shadow + thin accent ring) without moving its
 * neighbours. Visual only: pass the focus chain in [modifier].
 */
@Composable
fun BtvPosterCard(
    imageUrl: String?,
    title: String,
    focused: Boolean,
    modifier: Modifier = Modifier,
    meta: String? = null,
    badge: String? = null,
    isWatched: Boolean = false,
    progress: Float? = null,
    width: Dp = BtvDimens.posterWidth,
    /** Fit for channel logos (shown whole on the card surface), Crop for posters. */
    logo: Boolean = false
) {
    val colors = BtvTheme.colors
    val scale by animateFloatAsState(if (focused) BtvMotion.FOCUS_SCALE else 1f, tween(BtvMotion.FOCUS_MS), label = "posterScale")
    val elevation by animateDpAsState(if (focused) 14.dp else 0.dp, tween(BtvMotion.FOCUS_MS), label = "posterElevation")
    val ring by animateColorAsState(
        if (focused) colors.focusRing else Color.Transparent,
        tween(BtvMotion.FOCUS_MS),
        label = "posterRing"
    )
    val titleColor by animateColorAsState(
        if (focused) colors.textPrimary else colors.textSecondary,
        tween(BtvMotion.FOCUS_MS),
        label = "posterTitle"
    )

    Column(modifier = modifier.width(width)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(BtvDimens.POSTER_RATIO)
                // Grows upwards from its base: the title underneath is never covered.
                .graphicsLayer {
                    scaleX = scale
                    scaleY = scale
                    transformOrigin = TransformOrigin(0.5f, 1f)
                }
                .shadow(elevation, BtvShapes.card, clip = false, ambientColor = Color.Black, spotColor = Color.Black)
                .clip(BtvShapes.card)
                .background(colors.surface2)
                .border(BtvDimens.focusBorder, ring, BtvShapes.card)
        ) {
            // Initial in place of missing artwork (never behind a logo, which doesn't cover it).
            if (imageUrl.isNullOrBlank()) Text(
                title.take(1).uppercase(),
                modifier = Modifier.align(Alignment.Center),
                color = colors.textFaint,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold
            )
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(imageUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize().then(if (logo) Modifier.padding(18.dp) else Modifier),
                contentScale = if (logo) ContentScale.Fit else ContentScale.Crop,
                // Banners ("VOST", "4K") and titles sit at the top of IPTV
                // posters: when one is taller than the card, crop the bottom.
                alignment = Alignment.TopCenter
            )

            if (badge != null) {
                Text(
                    text = badge,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .background(com.btv.ui.theme.BtvGreen, BtvShapes.small)
                        .padding(horizontal = 6.dp, vertical = 2.dp),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = colors.onAccent,
                    maxLines = 1,
                    softWrap = false
                )
            }

            if (isWatched) {
                Box(
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .size(22.dp)
                        .background(Color.Black.copy(alpha = 0.65f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_lucide_check),
                        contentDescription = "Vu",
                        tint = BtvGreenBright,
                        modifier = Modifier.size(13.dp)
                    )
                }
            }

            if (progress != null && progress > 0f) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp)
                        .background(Color.White.copy(alpha = 0.22f))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth(progress.coerceIn(0f, 1f))
                            .fillMaxHeight()
                            .background(BtvGreenBright)
                    )
                }
            }
        }

        Spacer(Modifier.height(8.dp))
        Text(
            text = title,
            style = BtvType.title.copy(fontSize = 14.sp, lineHeight = 18.sp),
            color = titleColor,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
        if (!meta.isNullOrEmpty()) {
            Text(
                text = meta,
                style = BtvType.meta.copy(fontSize = 12.sp),
                color = colors.textMuted,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
