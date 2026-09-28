package com.getaudio.app.ui.screen

import android.content.ClipboardManager
import android.content.ClipData
import android.content.Context
import android.os.SystemClock
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.getaudio.app.ui.formatDuration
import com.getaudio.app.ui.formatSize
import com.getaudio.app.ui.platformLabel
import kotlinx.coroutines.launch

class ExtractViewModel(private val repo: TrackRepository, private val version: String) : ViewModel() {

    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<TrackEntity?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var diagnostics by mutableStateOf<List<String>>(emptyList())
        private set
    var directFailed by mutableStateOf(false)
        private set
    var usingServer by mutableStateOf(false)
        private set
    private var startedAt = 0L

    private fun record(message: String) {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        diagnostics = (diagnostics + "[+${seconds}s] $message").takeLast(60)
    }

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            usingServer = false
            directFailed = false
            error = null
            result = null
            startedAt = SystemClock.elapsedRealtime()
            diagnostics = listOf("GetAudio $version · 手机直提取（未使用 GetAudio 服务器）")
            repo.extract(text, ::record)
                .onSuccess { result = it; input = "" }
                .onFailure {
                    error = ApiProvider.humanMessage(it)
                    directFailed = true
                    record("提取失败：${it.javaClass.simpleName}")
                }
            busy = false
        }
    }

    fun submitViaServer() {
        val text = input.trim()
        if (text.isEmpty() || busy || !directFailed) return
        viewModelScope.launch {
            busy = true
            usingServer = true
            error = null
            record("手动选择服务器提取")
            repo.extractViaServer(text, ::record)
                .onSuccess {
                    result = it
                    input = ""
                    directFailed = false
                }
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

    // 从别的 App 分享进来的内容优先；否则试试剪贴板
    LaunchedEffect(prefill) {
        if (!prefill.isNullOrBlank()) {
            vm.input = prefill
            onSharedConsumed()
            vm.submit()
        } else if (vm.input.isBlank()) vm.pasteFromClipboard(context)
    }

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("粘贴分享链接", style = MaterialTheme.typography.titleMedium)
        Text(
            "整段分享文案直接贴进来就行，不用自己抠链接。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        OutlinedTextField(
            value = vm.input,
            onValueChange = { vm.input = it },
            label = { Text("分享文本 / 链接 / BV 号") },
            minLines = 3,
            modifier = Modifier.fillMaxWidth(),
        )

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::submit, enabled = vm.input.isNotBlank() && !vm.busy) {
                Text(if (vm.busy) "提取中…" else "提取音频")
            }
            OutlinedButton(onClick = { vm.pasteFromClipboard(context) }, enabled = !vm.busy) {
                Text("读剪贴板")
            }
        }

        if (vm.busy) {
            LinearProgressIndicator(Modifier.fillMaxWidth())
            Text(
                if (vm.usingServer) "服务器正在提取，完成后会下载到手机；长视频可能要等一会儿。"
                else "手机正在下载并抽取音轨，长视频可能要等一会儿。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }

        if (vm.directFailed) {
            OutlinedButton(onClick = vm::submitViaServer, enabled = !vm.busy && vm.input.isNotBlank()) {
                Text("通过服务器提取")
            }
            Text(
                "仅点击此按钮才会将分享文本发给设置中的服务器；需要先填写并保存服务器地址。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.result?.let { t ->
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("提取成功", style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(t.title, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "${platformLabel(t.platform)} · ${t.uploader ?: "-"} · " +
                            "${formatDuration(t.durationSec)} · ${formatSize(t.audioBytes)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text("已加入音频库，去「音频库」页播放。",
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }

        if (vm.diagnostics.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("提取诊断", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "遇到失败时复制下面的信息发给开发者。只记录步骤、状态码和大小，不记录 Cookie 或完整媒体地址。",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    vm.diagnostics.forEach { line ->
                        Text(line, style = MaterialTheme.typography.bodySmall)
                    }
                    OutlinedButton(onClick = {
                        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        clipboard.setPrimaryClip(ClipData.newPlainText(
                            "GetAudio 提取诊断", vm.diagnostics.joinToString("\n"),
                        ))
                    }) { Text("复制诊断") }
                }
            }
        }
    }
}
