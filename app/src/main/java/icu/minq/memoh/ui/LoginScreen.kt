package icu.minq.memoh.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.*
import androidx.compose.ui.unit.dp
import icu.minq.memoh.data.UiState

@Composable internal fun LoginScreen(state: UiState, login: (String, String, String, Boolean) -> Unit, forgetLogin: () -> Unit) {
    var server by rememberSaveable { mutableStateOf(state.rememberedLogin?.server.orEmpty()) }
    var username by rememberSaveable { mutableStateOf(state.rememberedLogin?.username.orEmpty()) }
    var password by remember { mutableStateOf(state.rememberedLogin?.password.orEmpty()) }
    var rememberLogin by rememberSaveable { mutableStateOf(state.rememberedLogin != null) }
    var showPassword by remember { mutableStateOf(false) }
    val focus = LocalFocusManager.current
    val canLogin = server.isNotBlank() && username.isNotBlank() && password.isNotBlank() && !state.loading
    val submit = { if (canLogin) { focus.clearFocus(); login(server, username, password, rememberLogin) }; Unit }
    val dots = MaterialTheme.colorScheme.outlineVariant
    Box(Modifier.fillMaxSize().imePadding()) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 22.dp.toPx()
            for (x in 0..(size.width / step).toInt()) for (y in 0..(size.height / step).toInt()) {
                val offset = Offset(x * step, y * step)
                // Quiet dot-matrix background, as in the official login shell.
                drawCircle(dots.copy(alpha = .45f), .7.dp.toPx(), offset)
            }
        }
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(horizontal = 28.dp, vertical = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Surface(color = MaterialTheme.colorScheme.background.copy(alpha = .96f), shape = RoundedCornerShape(24.dp)) {
                Column(Modifier.widthIn(max = 360.dp).fillMaxWidth().padding(vertical = 24.dp, horizontal = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    MemohLogo(56.dp)
                    Spacer(Modifier.height(20.dp))
                    Text("登录", style = MaterialTheme.typography.headlineMedium)
                    Spacer(Modifier.height(8.dp))
                    Text("开始与 Memoh 对话", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.height(30.dp))
                    LoginField(server, { server = it }, "服务器地址", "请输入服务器 URL", enabled = !state.loading,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, imeAction = ImeAction.Next))
                    Spacer(Modifier.height(10.dp))
                    LoginField(username, { username = it }, "用户名", "请输入用户名", enabled = !state.loading,
                        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next))
                    Spacer(Modifier.height(10.dp))
                    LoginField(password, { password = it }, "密码", "请输入密码", enabled = !state.loading,
                        visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done), keyboardActions = KeyboardActions(onDone = { submit() }),
                        trailing = { QuietIconButton(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, if (showPassword) "隐藏密码" else "显示密码", { showPassword = !showPassword }) })
                    Spacer(Modifier.height(8.dp))
                    Row(Modifier.fillMaxWidth().toggleable(value = rememberLogin, enabled = !state.loading, role = androidx.compose.ui.semantics.Role.Checkbox) {
                        rememberLogin = it
                        if (!it) forgetLogin()
                    }, verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(rememberLogin, null, enabled = !state.loading)
                        Text("记住登录信息", style = MaterialTheme.typography.bodyMedium)
                    }
                    Spacer(Modifier.height(8.dp))
                    Button(submit, enabled = canLogin, modifier = Modifier.fillMaxWidth().heightIn(min = 50.dp), shape = RoundedCornerShape(8.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.onSurface, contentColor = MaterialTheme.colorScheme.surface)) {
                        if (state.loading) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                        else Text("继续")
                    }
                    Spacer(Modifier.height(18.dp))
                    Text(if (rememberLogin) "服务器、用户名和密码将加密保存在此设备" else "通过 HTTPS 安全连接", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable private fun LoginField(
    value: String, change: (String) -> Unit, label: String, placeholder: String, enabled: Boolean,
    keyboardOptions: KeyboardOptions, keyboardActions: KeyboardActions = KeyboardActions.Default,
    visualTransformation: VisualTransformation = VisualTransformation.None, trailing: (@Composable () -> Unit)? = null,
) {
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    BasicTextField(value, change,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)
            .border(1.dp, if (focused) MaterialTheme.colorScheme.outline else MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(8.dp))
            .semantics { contentDescription = label },
        enabled = enabled, singleLine = true, keyboardOptions = keyboardOptions, keyboardActions = keyboardActions,
        interactionSource = interaction, visualTransformation = visualTransformation,
        textStyle = MaterialTheme.typography.bodyMedium.copy(color = MaterialTheme.colorScheme.onSurface),
        cursorBrush = SolidColor(MaterialTheme.colorScheme.primary), decorationBox = { inner ->
            Row(Modifier.padding(start = 14.dp, end = if (trailing == null) 14.dp else 0.dp).heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f).padding(vertical = 10.dp)) {
                    if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    inner()
                }
                trailing?.invoke()
            }
        })
}
