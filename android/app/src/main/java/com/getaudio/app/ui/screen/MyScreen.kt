package com.getaudio.app.ui.screen

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.getaudio.app.data.local.AppDatabase
import com.getaudio.app.data.repo.Settings
import com.getaudio.app.data.repo.DiagnosticsStore
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MyScreen(onCategories: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by AppDatabase.get(context).trackDao().observeAll().collectAsState(initial = emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var showAccount by remember { mutableStateOf(false) }
    val account by com.getaudio.app.data.repo.AccountStore.current.collectAsState()
    var showLogin by remember { mutableStateOf(false) }
    var showAgent by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf(Settings.DEFAULT_SERVER_URL) }
    var serverError by remember { mutableStateOf<String?>(null) }
    var serverCheck by remember { mutableStateOf<String?>(null) }
    var checkingServer by remember { mutableStateOf(false) }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知" }
    val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(DiagnosticsStore.lines.joinToString("\n").toByteArray()) }
                ?: error("无法创建文件")
        }
    }
    val bytes = tracks.sumOf { it.localPath?.let { path -> File(path).takeIf(File::exists)?.length() } ?: 0L }
    if (showAccount) {
        Column {
            AreaButton(onClick = { showAccount = false }) { Text("‹ 返回") }
            AccountScreen()
        }
        return
    }
    if (showLogin) {
        Column {
            AreaButton(onClick = { showLogin = false }) { Text("‹ 返回") }
            LoginScreen()
        }
        return
    }
    if (showAgent) {
        Column {
            AreaButton(onClick = { showAgent = false }) { Text("‹ 返回") }
            AgentScreen()
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth().regionFrame().clickable { showAccount = true }.padding(16.dp)) {
            Icon(Icons.Default.AccountCircle, null, Modifier.size(52.dp)); Spacer(Modifier.width(12.dp))
            Column { Text(account?.maskedPhone ?: "访客", style = MaterialTheme.typography.titleMedium); Text(if (account == null) "点击登录账号" else "账号管理", style = MaterialTheme.typography.bodySmall) }
        }

        AreaAction(onClick = { scope.launch { serverUrl = Settings(context).serverUrlNow(); serverError = null; serverCheck = null; dialog = "server" } }) { Text("服务器设置") }
        AreaAction(onClick = { showLogin = true }) { Text("抖音 / B站平台登录") }
        AreaAction(onClick = onCategories) { Text("类别管理") }
        AreaAction(onClick = { dialog = "storage" }) { Text("存储空间：${bytes / 1024 / 1024} MB") }
        AreaAction(onClick = { dialog = "feedback" }) { Text("意见反馈") }
        AreaAction(onClick = { dialog = "diagnostics" }) { Text("导出诊断日志") }
        AreaAction(onClick = { showAgent = true }) { Text("音频助手") }

        AreaAction(onClick = { dialog = "about" }) { Text("版本 $version") }
    }
    when (dialog) {
        "storage" -> InfoDialog("存储空间", "本机已保存 ${tracks.size} 首音频，约 ${bytes / 1024 / 1024} MB。") { dialog = null }
        "feedback" -> InfoDialog("意见反馈", "意见反馈功能暂未开放。") { dialog = null }
        "diagnostics" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("导出诊断日志") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { Text("遇到提取问题时，可以导出最近一次诊断步骤供排查。不会包含 Cookie 或完整媒体地址。")
                AreaButton(onClick = { diagnosticExport.launch("GetAudio-diagnostics.txt"); dialog = null }, enabled = DiagnosticsStore.lines.isNotEmpty()) { Text("导出诊断日志") }
            } }, confirmButton = { AreaButton(onClick = { dialog = null }) { Text("关闭") } })
        "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("GetAudio $version") }, text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("分享链接提取音频，分类、播放和管理均可在本机完成。")
            AreaButton(onClick = { scope.launch { serverUrl = Settings(context).serverUrlNow() }; dialog = "server" }) { Text("开发者选项") }
        } }, confirmButton = { AreaButton(onClick = { dialog = null }) { Text("关闭") } })
        "server" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("服务器设置") },
            text = { Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(serverUrl, { serverUrl = it; serverError = null; serverCheck = null }, label = { Text("服务器地址") }, singleLine = true,
                    isError = serverError != null, supportingText = { serverError?.let { Text(it) } })
                if (serverUrl.trimEnd('/') == Settings.DEFAULT_SERVER_URL.trimEnd('/')) Text("当前为 tx 服务器，可通过 Wi-Fi 或移动网络直接提取和同步音频。", style = MaterialTheme.typography.bodySmall)
                if (Settings.DEFAULT_SERVER_URL.isNotBlank()) AreaButton(onClick = { serverUrl = Settings.DEFAULT_SERVER_URL; serverError = null; serverCheck = null }, tone = ActionTone.SYNC) { Text("恢复默认服务器") }
                AreaButton(onClick = {
                    val address = serverUrl.trim().trimEnd('/')
                    checkingServer = true
                    scope.launch {
                        serverCheck = runCatching {
                            kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                                val request = okhttp3.Request.Builder().url("$address/health").build()
                                com.getaudio.app.data.repo.ApiProvider.okHttp().newBuilder()
                                    .callTimeout(10, java.util.concurrent.TimeUnit.SECONDS).build()
                                    .newCall(request).execute().use { response ->
                                        require(response.isSuccessful && org.json.JSONObject(response.body?.string().orEmpty()).optBoolean("ok")) { "服务器未返回正常状态" }
                                    }
                            }
                            "服务器连接正常"
                        }.getOrElse { "连接未成功，请检查地址和网络" }
                        checkingServer = false
                    }
                }, enabled = !checkingServer && serverUrl.isNotBlank(), tone = ActionTone.SYNC) { Text(if (checkingServer) "检测中…" else "测试连接") }
                serverCheck?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            } },
            confirmButton = { AreaButton(onClick = {
                val uri = android.net.Uri.parse(serverUrl.trim())
                if (uri.scheme !in listOf("http", "https") || uri.host.isNullOrBlank()) serverError = "请输入有效的 http 或 https 地址"
                else scope.launch { Settings(context).setServerUrl(serverUrl); dialog = null }
            }, tone = ActionTone.CONFIRM) { Text("保存") } }, dismissButton = { AreaButton(onClick = { dialog = null }) { Text("取消") } })
    }
}

@Composable
private fun InfoDialog(title: String, text: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(text) }, confirmButton = { AreaButton(onClick = close) { Text("关闭") } })
}
