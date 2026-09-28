package com.getaudio.app.ui.screen

import android.content.ClipboardManager
import android.content.Context
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

class ExtractViewModel(private val repo: TrackRepository) : ViewModel() {

    var input by mutableStateOf("")
    var busy by mutableStateOf(false)
        private set
    var result by mutableStateOf<TrackEntity?>(null)
        private set
    var error by mutableStateOf<String?>(null)
        private set

    fun submit() {
        val text = input.trim()
        if (text.isEmpty() || busy) return
        viewModelScope.launch {
            busy = true
            error = null
            result = null
            repo.extract(text)
                .onSuccess { result = it; input = "" }
                .onFailure { error = ApiProvider.humanMessage(it) }
            busy = false
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
            ExtractViewModel(TrackRepository(context)) as T
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
                "手机正在下载并抽取音轨，长视频可能要等一会儿。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        vm.error?.let {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text(it, Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
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
    }
}
