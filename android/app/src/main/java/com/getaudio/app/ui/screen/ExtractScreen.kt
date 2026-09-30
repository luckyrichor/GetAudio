package com.getaudio.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.getaudio.app.data.local.TrackEntity
import com.getaudio.app.data.repo.ApiProvider
import com.getaudio.app.data.repo.TrackRepository
import com.getaudio.app.data.repo.DiagnosticsStore
import com.getaudio.app.data.repo.Settings
import com.getaudio.app.ui.formatDuration
import kotlinx.coroutines.launch

class ExtractViewModel(private val repo: TrackRepository, private val version: String) : ViewModel() {

    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<TrackEntity?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var usingServer by mutableStateOf(false)
        private set
    private var startedAt = 0L

    private fun record(message: String) {
        DiagnosticsStore.record(startedAt, message)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            usingServer = false
            error = null
            result = null
            startedAt = SystemClock.elapsedRealtime()
            DiagnosticsStore.beginLocal(version)
            repo.extract(text, ::record)
                .onSuccess { result = it }
                .onFailure {
                    error = ApiProvider.humanMessage(it)
                    record("提取失败：${it.javaClass.simpleName}")
                }
            busy = false
        }
    }

    fun submitViaServer() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            usingServer = true
            error = null
            result = null
            startedAt = SystemClock.elapsedRealtime()
            DiagnosticsStore.beginServer(version)
            record("手动选择服务器提取")
            repo.extractViaServer(text, ::record)
                .onSuccess { result = it }
                .onFailure {
                    error = ApiProvider.humanMessage(it)
                    record("服务器提取失败：${it.javaClass.simpleName}")
                }
            busy = false
            usingServer = false
        }
    }

    /**
     * 读剪贴板自动填入。
     *
     * 这是整个 App 最关键的体验点：用户刚从抖音复制完分享文案切过来，
     * 手动粘贴多一步就少一分用。
     */
    fun pasteFromClipboard(context: Context) {
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return
        val text = cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
        if (text.isNotBlank() && looksLikeShareLink(text)) input = text
    }

    private fun looksLikeShareLink(text: String): Boolean =
        listOf("bilibili.com", "b23.tv", "douyin.com", "BV").any { text.contains(it) }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            ExtractViewModel(
                TrackRepository(context),
                context.packageManager.getPackageInfo(context.packageName, 0).versionName ?: "未知版本",
            ) as T
    }
}

@Composable
fun ExtractScreen(prefill: String? = null, onSharedConsumed: () -> Unit = {}) {
    val context = LocalContext.current
    val vm: ExtractViewModel = viewModel(factory = ExtractViewModel.Factory(context))
    val snackbar = remember { SnackbarHostState() }
    var pasteDialog by remember { mutableStateOf(false) }
    val serverUrl by remember(context) { Settings(context).serverUrl }.collectAsState(initial = "")

    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            vm.input = prefill
            onSharedConsumed()
            vm.submit()
        } else if (vm.input.isBlank()) vm.pasteFromClipboard(context)
    }
    LaunchedEffect(vm.result) {
        vm.result?.let { snackbar.showSnackbar("${it.title} · ${formatDuration(it.durationSec)} · 已加入音频库", withDismissAction = true, duration = SnackbarDuration.Short) }
    }
    LaunchedEffect(vm.error) {
        vm.error?.let { snackbar.showSnackbar(it, withDismissAction = true, duration = SnackbarDuration.Short) }
    }

    Scaffold(snackbarHost = { SnackbarHost(snackbar) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("提取音频", style = MaterialTheme.typography.titleLarge)
            Button(onClick = { if (vm.input.isBlank()) vm.pasteFromClipboard(context); if (vm.input.isBlank()) pasteDialog = true else vm.submit() }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.busy && !vm.usingServer) "提取中…" else "本地提取")
            }
            OutlinedButton(onClick = { vm.pasteFromClipboard(context); pasteDialog = true }, enabled = !vm.busy, modifier = Modifier.fillMaxWidth()) { Text("粘贴文本") }
            OutlinedButton(onClick = { if (vm.input.isBlank()) vm.pasteFromClipboard(context); if (vm.input.isBlank()) pasteDialog = true else vm.submitViaServer() }, enabled = !vm.busy && serverUrl.isNotBlank(), modifier = Modifier.fillMaxWidth()) {
                Text(if (vm.busy && vm.usingServer) "提取中…" else "服务器提取")
            }
            if (serverUrl.isBlank()) Text("未配置服务器，可在「我的 → 关于 → 开发者选项」设置", style = MaterialTheme.typography.bodySmall)
            if (vm.busy) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text(if (vm.usingServer) "服务器正在提取并下载到手机" else "手机正在解析并下载音频", style = MaterialTheme.typography.bodySmall)
            }
            ExtractionDiagnostics()
        }
    }
    if (pasteDialog) AlertDialog(onDismissRequest = { pasteDialog = false }, title = { Text("粘贴分享文本") },
        text = { OutlinedTextField(vm.input, { vm.input = it }, label = { Text("分享文本或链接") }, minLines = 3, modifier = Modifier.fillMaxWidth()) },
        confirmButton = { Row {
            TextButton(onClick = { pasteDialog = false; vm.submit() }, enabled = vm.input.isNotBlank()) { Text("本地提取") }
            TextButton(onClick = { pasteDialog = false; vm.submitViaServer() }, enabled = vm.input.isNotBlank() && serverUrl.isNotBlank()) { Text("服务器提取") }
        } }, dismissButton = { TextButton(onClick = { pasteDialog = false }) { Text("取消") } })
}
