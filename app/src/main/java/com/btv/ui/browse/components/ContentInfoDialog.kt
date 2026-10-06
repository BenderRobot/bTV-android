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
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.btv.ui.browse.ContentItem
import com.btv.ui.theme.BtvGreenBright
import com.btv.ui.theme.BtvTheme
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
        Row(
            modifier = Modifier
                .fillMaxWidth(0.82f)
                .fillMaxHeight(0.84f)
                .clip(RoundedCornerShape(16.dp))
                .background(colors.surface)
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
                .padding(28.dp)
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
                    .clip(RoundedCornerShape(10.dp))
                    .background(colors.surface2),
                contentScale = ContentScale.Crop,
                alignment = Alignment.TopCenter
            )
            Spacer(Modifier.width(28.dp))
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .verticalScroll(scrollState)
            ) {
                Text(content.name, color = colors.textPrimary, fontSize = 28.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(10.dp))
                val meta = listOfNotNull(
                    content.year, content.duration, content.genre, content.country
                ).filter { it.isNotBlank() }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (!content.rating.isNullOrBlank()) {
                        Text("IMDb ${content.rating}", color = colors.accentOnSurface, fontSize = 17.sp, fontWeight = FontWeight.Bold)
                        if (meta.isNotEmpty()) Spacer(Modifier.width(16.dp))
                    }
                    Text(meta.joinToString("   ·   "), color = colors.textSecondary, fontSize = 17.sp)
                }
                if (!content.plot.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text(content.plot, color = colors.textPrimary, fontSize = 19.sp, lineHeight = 28.sp)
                }
                if (!content.director.isNullOrBlank()) {
                    Spacer(Modifier.height(18.dp))
                    Text("Réalisateur : ${content.director}", color = colors.textSecondary, fontSize = 16.sp)
                }
                val photos = content.castPhotos
                if (!photos.isNullOrEmpty()) {
                    Spacer(Modifier.height(20.dp))
                    Text("Casting", color = colors.textPrimary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
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
                    Text("Casting : ${content.cast}", color = colors.textSecondary, fontSize = 16.sp, lineHeight = 24.sp)
                }
                Spacer(Modifier.height(24.dp))
                Text("Haut / Bas pour faire défiler · Retour pour fermer", color = colors.textMuted, fontSize = 13.sp)
            }
        }
    }
}
