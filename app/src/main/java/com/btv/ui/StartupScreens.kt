package com.btv.ui

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.R
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvTheme

/** True when Android reports a network that claims Internet access. */
fun hasNetwork(context: Context): Boolean {
    val manager = context.getSystemService(ConnectivityManager::class.java) ?: return true
    val capabilities = manager.getNetworkCapabilities(manager.activeNetwork) ?: return false
    return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
}

/** Shown while the saved account is checked against the server - never a silent black screen. */
@Composable
fun StartupConnectingScreen() {
    val colors = BtvTheme.colors
    Column(
        Modifier.fillMaxSize().background(colors.bgBlack),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Image(painterResource(R.drawable.btv_icon), contentDescription = "bTV", modifier = Modifier.size(96.dp))
        Spacer(Modifier.height(24.dp))
        CircularProgressIndicator(color = BtvGreen)
        Spacer(Modifier.height(14.dp))
        Text("Connexion au serveur…", color = colors.textSecondary, fontSize = 15.sp)
    }
}

/**
 * The saved account couldn't be checked: no network or no answer. The
 * credentials are kept - retrying is usually all it takes (Fire TV woken up
 * before its Wi-Fi).
 */
@Composable
fun StartupUnreachableScreen(noNetwork: Boolean, canEditAccount: Boolean, onRetry: () -> Unit, onEditAccount: () -> Unit) {
    val colors = BtvTheme.colors
    val retryFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        repeat(20) {
            try {
                retryFocus.requestFocus()
                return@LaunchedEffect
            } catch (_: IllegalStateException) {
                kotlinx.coroutines.delay(30)
            }
        }
    }
    Box(Modifier.fillMaxSize().background(colors.bgBlack), contentAlignment = Alignment.Center) {
        Column(
            Modifier.width(560.dp).background(colors.surface, RoundedCornerShape(14.dp)).padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                if (noNetwork) "Pas de connexion Internet" else "Serveur injoignable",
                color = colors.textPrimary, fontSize = 20.sp, fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(10.dp))
            Text(
                if (noNetwork) "Vérifiez le Wi-Fi ou le câble réseau de l'appareil, puis réessayez."
                else "Le serveur IPTV ne répond pas pour le moment. Votre compte est conservé.",
                color = colors.textSecondary, fontSize = 13.sp, textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(22.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                StartupButton("Réessayer", primary = true, onClick = onRetry, modifier = Modifier.focusRequester(retryFocus))
                if (canEditAccount) StartupButton("Modifier le compte", primary = false, onClick = onEditAccount)
            }
        }
    }
}

@Composable
private fun StartupButton(label: String, primary: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    var focused by remember { mutableStateOf(false) }
    val colors = BtvTheme.colors
    Text(
        label,
        modifier = modifier
            .onFocusChanged { focused = it.isFocused }
            .onKeyEvent { event ->
                if (event.type == KeyEventType.KeyDown && (event.key == Key.DirectionCenter || event.key == Key.Enter)) {
                    onClick(); true
                } else false
            }
            .focusable()
            .clickable(onClick = onClick)
            .background(if (primary) BtvGreen.copy(alpha = if (focused) 1f else 0.75f) else colors.surface2, RoundedCornerShape(10.dp))
            .border(2.dp, if (focused) Color.White else Color.Transparent, RoundedCornerShape(10.dp))
            .padding(horizontal = 22.dp, vertical = 12.dp),
        color = Color.White,
        fontSize = 14.sp,
        fontWeight = FontWeight.Bold
    )
}
