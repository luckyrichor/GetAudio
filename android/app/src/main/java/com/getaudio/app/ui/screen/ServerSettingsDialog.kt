package com.getaudio.app.ui.screen

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.getaudio.app.data.repo.ApiProvider
import com.getaudio.app.data.repo.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun ServerSettingsDialog(close: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf(Settings.DEFAULT_SERVER_URL) }
    var username by remember { mutableStateOf(ApiProvider.serverUsername(context)) }
    var password by remember { mutableStateOf("") }
    var configured by remember { mutableStateOf(ApiProvider.hasServerAccess(context)) }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    var failed by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { address = Settings(context).serverUrlNow() }

    fun check(save: Boolean) {
        busy = true
        message = null
        scope.launch {
            try {
                val result = runCatching {
                    val status = withContext(Dispatchers.IO) { ApiProvider.testServerAccess(context, address, username, password, save) }
                    if (save) Settings(context).setServerUrl(address)
                    status
                }
                failed = result.isFailure
                message = result.getOrElse { it.message ?: "检查失败，请检查网络或服务器配置" }
                if (result.isSuccess && save) {
                    password = ""
                    configured = ApiProvider.hasServerAccess(context)
                }
            } finally {
                busy = false
            }
        }
    }

    AlertDialog(
        onDismissRequest = { if (!busy) { password = ""; close() } },
        title = { Text("服务器设置") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(address, { address = it; message = null }, label = { Text("服务器地址") }, singleLine = true, enabled = !busy)
                Text("tx 通过 Wi-Fi 或移动网络提供提取和同步。以下是服务器访问凭据，与手机号账号和平台登录不同。", style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(username, { username = it; message = null }, label = { Text("服务器用户名") }, singleLine = true, enabled = !busy)
                OutlinedTextField(password, { password = it; message = null }, label = { Text("服务器密码") }, singleLine = true,
                    visualTransformation = PasswordVisualTransformation(), enabled = !busy,
                    placeholder = { Text(if (configured) "已保存，留空保留原密码" else "请输入服务器密码") })
                Text("密码仅保存在此应用私有目录，不写入诊断日志。保存前会验证；验证失败不会覆盖原配置。", style = MaterialTheme.typography.bodySmall)
                AreaButton(onClick = { address = Settings.DEFAULT_SERVER_URL; message = null }, enabled = !busy, tone = ActionTone.SYNC) { Text("恢复默认服务器") }
                AreaButton(onClick = { check(false) }, enabled = !busy && address.isNotBlank(), tone = ActionTone.SYNC) { Text(if (busy) "验证中…" else "测试连接及授权") }
                message?.let { Text(it, color = if (failed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall) }
            }
        },
        confirmButton = { AreaButton(onClick = { check(true) }, enabled = !busy, tone = ActionTone.CONFIRM) { Text("验证并保存") } },
        dismissButton = { AreaButton(onClick = { password = ""; close() }, enabled = !busy) { Text("关闭") } },
    )
}
