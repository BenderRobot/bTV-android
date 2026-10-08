package com.btv.ui.browse.components

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btv.ui.browse.ContentItem
import androidx.compose.foundation.border
import com.btv.ui.theme.BtvShapes
import com.btv.ui.components.consumeTaps
import com.btv.ui.components.onTap
import com.btv.ui.theme.BtvTheme
import com.btv.ui.theme.BtvType
import kotlinx.coroutines.launch

/**
 * Full details of a film or series, readable at a distance: the browse
 * band only has room for three lines of synopsis over the artwork. Up/Down
 * scroll the text, Back or OK closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ContentInfoDialog(content: ContentItem, onDismiss: () -> Unit) {
    val colors = BtvTheme.colors
    val scrollState = rememberScrollState()
    val scope = rememberCoroutineScope()
    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            if (runCatching { focusRequester.requestFocus() }.isSuccess) return@LaunchedEffect
            kotlinx.coroutines.delay(50)
        }
    }

    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        // Our own scrim, as dark as every other dialog of the app (the
        // platform's dim alone left the screen behind almost untouched).
        androidx.compose.foundation.layout.Box(
            Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color.Black.copy(alpha = 0.72f)).onTap { onDismiss() },
            contentAlignment = Alignment.Center
        ) {
        Row(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .consumeTaps()
                .fillMaxHeight(0.84f)
                .clip(BtvShapes.dialog)
                .background(colors.surface)
                .border(1.dp, colors.border, BtvShapes.dialog)
                .focusRequester(focusRequester)
                .focusable()
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown) return@onKeyEvent false
                    when (event.key) {
                        Key.DirectionDown -> { scope.launch { scrollState.animateScrollBy(220f) }; true }
                        Key.DirectionUp -> { scope.launch { scrollState.animateScrollBy(-220f) }; true }
                        Key.Back, Key.DirectionCenter, Key.Enter -> { onDismiss(); true }
                        else -> true
                    }
                }
                .padding(32.dp)
        ) {
            AsyncImage(
                model = ImageRequest.Builder(LocalContext.current)
                    .data(content.posterUrl ?: content.backdropUrl)
                    .crossfade(true)
                    .build(),
                contentDescription = content.name,
                modifier = Modifier
                    .width(220.dp)
                    .height(330.dp)
                    .clip(BtvShapes.card)
                    .background(colors.surface2),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
            Spacer(Modifier.width(32.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scrollState)
            ) {
                val name = com.btv.util.displayName(content.name)
                Text(name.title, color = colors.textPrimary, style = BtvType.hero)
                if (name.tags.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { name.tags.forEach { com.btv.ui.components.BtvTag(it) } }
                }
                Spacer(Modifier.height(10.dp))
                val meta = listOfNotNull(
                    content.year, com.btv.util.displayDuration(content.duration), content.genre, content.country
                ).filter { it.isNotBlank() }
                val rating = content.rating?.takeIf { it.isNotBlank() && it.toFloatOrNull() != 0f }
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        if (rating != null) {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = colors.accentOnSurface, fontWeight = FontWeight.SemiBold)) {
                                append("★ $rating")
                            }
                            if (meta.isNotEmpty()) append("   ·   ")
                        }
                        append(meta.joinToString("   ·   "))
                    },
                    color = colors.textSecondary,
                    fontSize = 16.sp,
                    lineHeight = 24.sp
                )
                if (!content.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(content.plot, color = colors.textPrimary.copy(alpha = 0.88f), fontSize = 18.sp, lineHeight = 27.sp)
                }
                if (!content.director.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(
                        androidx.compose.ui.text.buildAnnotatedString {
                            withStyle(androidx.compose.ui.text.SpanStyle(color = colors.textMuted)) { append("Réalisation  ") }
                            append(content.director)
                        },
                        color = colors.textPrimary.copy(alpha = 0.9f),
                        fontSize = 16.sp
                    )
                }
                val photos = content.castPhotos
                if (!photos.isNullOrEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("Casting", color = colors.textPrimary, style = BtvType.section)
                    Spacer(Modifier.height(12.dp))
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(14.dp),
                        verticalArrangement = Arrangement.spacedBy(14.dp)
                    ) {
                        photos.forEach { person ->
                            CastAvatar(person, modifier = Modifier.width(92.dp), size = 72.dp)
                        }
                    }
                } else if (!content.cast.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Avec ${content.cast}", color = colors.textSecondary, fontSize = 16.sp, lineHeight = 24.sp)
                }
                Spacer(Modifier.height(24.dp))
                Text("Haut / Bas pour faire défiler · Retour pour fermer", color = colors.textMuted, style = BtvType.meta)
            }
        }
        }
    }
}
