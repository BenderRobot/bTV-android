package com.btv.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.btv.data.repository.AuthRepository
import com.btv.ui.theme.BtvGreen
import com.btv.ui.theme.BtvTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * D-pad text field for this form, same fix as the browse screens' search
 * fields (CompactSearchField): explicit text/label colors from the app
 * palette (BtvTheme.colors) rather than the system light/dark theme
 * (Material3's default OutlinedTextField pulled its text color from
 * MaterialTheme.colorScheme.onSurface, which followed the system and could
 * be unreadable against this screen's background). Also `readOnly` until Center/Enter explicitly
 * marks it editable, so navigating here with D-pad Up/Down only highlights
 * the field - the keyboard no longer pops just from moving through it.
 */
@Composable
private fun LoginField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    focusRequester: FocusRequester,
    modifier: Modifier = Modifier,
    isPassword: Boolean = false,
    onDpadUp: (() -> Boolean)? = null,
    onDpadDown: (() -> Boolean)? = null
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    var isFocused by remember { mutableStateOf(false) }
    var editable by remember { mutableStateOf(false) }

    Column(modifier = modifier) {
        Text(text = label, color = BtvTheme.colors.textMuted, fontSize = 11.sp)
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .background(BtvTheme.colors.surface2, RoundedCornerShape(6.dp))
                .border(
                    1.dp,
                    if (isFocused) BtvGreen else BtvTheme.colors.border,
                    RoundedCornerShape(6.dp)
                )
                .padding(horizontal = 12.dp, vertical = 9.dp)
        ) {
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                textStyle = TextStyle(color = BtvTheme.colors.textPrimary, fontSize = 15.sp),
                singleLine = true,
                readOnly = !editable,
                cursorBrush = SolidColor(BtvGreen),
                visualTransformation = if (isPassword) PasswordVisualTransformation() else VisualTransformation.None,
                modifier = Modifier
                    .fillMaxWidth()
                    .focusRequester(focusRequester)
                    .onFocusChanged { state ->
                        isFocused = state.isFocused
                        if (!state.isFocused) editable = false
                    }
                    .onPreviewKeyEvent { keyEvent ->
                        if (keyEvent.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                        when (keyEvent.key) {
                            Key.DirectionUp -> if (editable) false else onDpadUp?.invoke() ?: false
                            Key.DirectionDown -> if (editable) false else onDpadDown?.invoke() ?: false
                            Key.DirectionCenter, Key.Enter -> {
                                editable = true
                                keyboardController?.show()
                                true
                            }
                            Key.Back -> {
                                if (editable) {
                                    editable = false
                                    keyboardController?.hide()
                                    true
                                } else {
                                    false
                                }
                            }
                            else -> false
                        }
                    }
            )
        }
    }
}

@Composable
fun LoginScreen(
    onLoginSuccess: (com.btv.data.model.AuthSession) -> Unit,
    repository: AuthRepository,
    // "Modifier le serveur" (Tizen editServer): current credentials prefilled,
    // the saved session kept until a new login succeeds, Back returns.
    prefill: com.btv.data.model.AuthSession? = null,
    onCancel: (() -> Unit)? = null
) {
    if (onCancel != null) androidx.activity.compose.BackHandler(onBack = onCancel)
    var serverUrl by remember { mutableStateOf(prefill?.serverUrl ?: "http://2.900900.me") }
    var username by remember { mutableStateOf(prefill?.username.orEmpty()) }
    var password by remember { mutableStateOf(prefill?.password.orEmpty()) }
    var error by remember { mutableStateOf<String?>(null) }
    var isLoading by remember { mutableStateOf(false) }

    val serverFocusRequester = remember { FocusRequester() }
    val usernameFocusRequester = remember { FocusRequester() }
    val passwordFocusRequester = remember { FocusRequester() }
    val buttonFocusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        try { serverFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
    }

    fun submit() {
        isLoading = true
        error = null
        CoroutineScope(Dispatchers.IO).launch {
            val result = repository.login(serverUrl, username, password)
            result.onSuccess { session ->
                CoroutineScope(Dispatchers.Main).launch {
                    onLoginSuccess(session)
                    isLoading = false
                }
            }.onFailure {
                CoroutineScope(Dispatchers.Main).launch {
                    error = it.message ?: "Échec de connexion"
                    isLoading = false
                }
            }
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(BtvTheme.colors.bgApp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Surface(
            shape = RoundedCornerShape(14.dp),
            color = BtvTheme.colors.surface,
            contentColor = BtvTheme.colors.textPrimary,
            modifier = Modifier
                .fillMaxWidth(0.34f)
                .padding(12.dp)
        ) {
            Column(
                modifier = Modifier.padding(18.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(
                    text = "CONNEXION IPTV",
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 18.sp
                )

                LoginField(
                    value = serverUrl,
                    onValueChange = { serverUrl = it },
                    label = "URL du serveur",
                    focusRequester = serverFocusRequester,
                    modifier = Modifier.fillMaxWidth(),
                    onDpadDown = {
                        try { usernameFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                        true
                    }
                )

                LoginField(
                    value = username,
                    onValueChange = { username = it },
                    label = "Nom d'utilisateur",
                    focusRequester = usernameFocusRequester,
                    modifier = Modifier.fillMaxWidth(),
                    onDpadUp = {
                        try { serverFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                        true
                    },
                    onDpadDown = {
                        try { passwordFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                        true
                    }
                )

                LoginField(
                    value = password,
                    onValueChange = { password = it },
                    label = "Mot de passe",
                    focusRequester = passwordFocusRequester,
                    modifier = Modifier.fillMaxWidth(),
                    isPassword = true,
                    onDpadUp = {
                        try { usernameFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                        true
                    },
                    onDpadDown = {
                        try { buttonFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                        true
                    }
                )

                if (error != null) {
                    Text(text = error ?: "", color = MaterialTheme.colorScheme.error, fontSize = 12.sp)
                }

                Button(
                    onClick = { submit() },
                    enabled = !isLoading,
                    contentPadding = ButtonDefaults.ContentPadding.let {
                        androidx.compose.foundation.layout.PaddingValues(horizontal = 16.dp, vertical = 8.dp)
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(buttonFocusRequester)
                        .onPreviewKeyEvent { keyEvent ->
                            if (keyEvent.type == KeyEventType.KeyDown && keyEvent.key == Key.DirectionUp) {
                                try { passwordFocusRequester.requestFocus() } catch (e: IllegalStateException) {}
                                true
                            } else {
                                false
                            }
                        }
                ) {
                    Text(if (isLoading) "Connexion..." else "SE CONNECTER", fontSize = 14.sp)
                }
            }
        }
    }
}
