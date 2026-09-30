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
import com.getaudio.app.player.PlayerHolder
import kotlinx.coroutines.launch
import java.io.File

@Composable
fun MyScreen(onCategories: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val tracks by AppDatabase.get(context).trackDao().observeAll().collectAsState(initial = emptyList())
    var dialog by remember { mutableStateOf<String?>(null) }
    var showLogin by remember { mutableStateOf(false) }
    var showAgent by remember { mutableStateOf(false) }
    var serverUrl by remember { mutableStateOf("") }
    val version = remember { context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知" }
    val diagnosticExport = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/plain")) { uri ->
        if (uri != null) runCatching {
            context.contentResolver.openOutputStream(uri)?.use { it.write(DiagnosticsStore.lines.joinToString("\n").toByteArray()) }
                ?: error("无法创建文件")
        }
    }
    val bytes = tracks.sumOf { it.localPath?.let { path -> File(path).takeIf(File::exists)?.length() } ?: 0L }
    if (showLogin) {
        Column {
            TextButton(onClick = { showLogin = false }) { Text("‹ 返回我的") }
            LoginScreen()
        }
        return
    }
    if (showAgent) {
        Column {
            TextButton(onClick = { showAgent = false }) { Text("‹ 返回我的") }
            AgentScreen()
        }
        return
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("我的", style = MaterialTheme.typography.headlineSmall)
        Row(Modifier.fillMaxWidth().clickable { showLogin = true }.padding(vertical = 16.dp)) {
            Icon(Icons.Default.AccountCircle, null, Modifier.size(52.dp)); Spacer(Modifier.width(12.dp))
            Column { Text("访客", style = MaterialTheme.typography.titleMedium); Text("点击登录", style = MaterialTheme.typography.bodySmall) }
        }
        HorizontalDivider()
        TextButton(onClick = onCategories) { Text("类别管理") }
        TextButton(onClick = { dialog = "storage" }) { Text("存储空间：${bytes / 1024 / 1024} MB") }
        TextButton(onClick = { dialog = "playback" }) { Text("播放设置") }
        TextButton(onClick = { dialog = "feedback" }) { Text("意见反馈 / 导出诊断日志") }
        TextButton(onClick = { showAgent = true }) { Text("音频助手") }
        HorizontalDivider()
        TextButton(onClick = { dialog = "about" }) { Text("GetAudio · 版本 $version") }
    }
    when (dialog) {
        "storage" -> InfoDialog("存储空间", "本机已保存 ${tracks.size} 首音频，约 ${bytes / 1024 / 1024} MB。") { dialog = null }
        "feedback" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("意见反馈") },
            text = { Column { Text("遇到提取问题时，可以导出最近一次诊断步骤供排查。不会包含 Cookie 或完整媒体地址。")
                TextButton(onClick = { diagnosticExport.launch("GetAudio-diagnostics.txt"); dialog = null }, enabled = DiagnosticsStore.lines.isNotEmpty()) { Text("导出诊断日志") }
            } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } })
        "playback" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("播放设置") }, text = { Column { PlayerHolder.Mode.entries.forEach { mode -> TextButton(onClick = { scope.launch { PlayerHolder.setMode(context, mode) }; dialog = null }) { Text(mode.label) } } } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } })
        "about" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("GetAudio $version") }, text = { Column {
            Text("分享链接提取音频，分类、播放和管理均可在本机完成。")
            TextButton(onClick = { scope.launch { serverUrl = Settings(context).serverUrlNow() }; dialog = "developer" }) { Text("开发者选项") }
        } }, confirmButton = { TextButton(onClick = { dialog = null }) { Text("关闭") } })
        "developer" -> AlertDialog(onDismissRequest = { dialog = null }, title = { Text("开发者选项") },
            text = { OutlinedTextField(serverUrl, { serverUrl = it }, label = { Text("服务器地址") }, singleLine = true) },
            confirmButton = { TextButton(onClick = { scope.launch { Settings(context).setServerUrl(serverUrl) }; dialog = null }) { Text("保存") } }, dismissButton = { TextButton(onClick = { dialog = null }) { Text("取消") } })
    }
}

@Composable
private fun InfoDialog(title: String, text: String, close: () -> Unit) {
    AlertDialog(onDismissRequest = close, title = { Text(title) }, text = { Text(text) }, confirmButton = { TextButton(onClick = close) { Text("关闭") } })
}
