package com.getaudio.app.ui.screen

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
import com.getaudio.app.data.remote.*
import com.getaudio.app.data.repo.ApiProvider
import com.getaudio.app.data.repo.Settings
import com.getaudio.app.data.repo.TrackRepository
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class AgentViewModel(context: Context) : ViewModel() {
    private val settings = Settings(context.applicationContext)
    private val tracks = TrackRepository(context.applicationContext)
    var serverReady by mutableStateOf(false)
        private set
    var busy by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var question by mutableStateOf("")
    var draft by mutableStateOf("")
    var editing by mutableStateOf<AgentMemoryOut?>(null)
    val dialogue = mutableStateListOf<Pair<String, String>>()
    val memories = mutableStateListOf<AgentMemoryOut>()

    private suspend fun apiAndUser(): Pair<GetAudioApi, String> {
        val url = settings.serverUrlNow()
        require(url.isNotBlank()) { "请先在「我的 → 服务器设置」填写服务器地址" }
        return ApiProvider.api(url) to settings.userId()
    }

    fun refresh() = viewModelScope.launch {
        error = null
        runCatching {
            val url = settings.serverUrlNow()
            serverReady = url.isNotBlank()
            if (!serverReady) emptyList() else {
                val (api, user) = apiAndUser()
                api.agentMemories(user)
            }
        }.onSuccess { memories.clear(); memories.addAll(it) }
            .onFailure { error = ApiProvider.humanMessage(it) }
    }

    fun ask() = viewModelScope.launch {
        val text = question.trim()
        if (text.isEmpty() || busy) return@launch
        question = ""
        busy = true
        error = null
        dialogue.add("我" to text)
        runCatching {
            val (api, user) = apiAndUser()
            val local = tracks.observeAll().first().asSequence().filter { it.id < 0 }.take(100)
                .map { AgentLocalTrack(it.id, it.title.take(200), it.uploader?.take(100),
                    it.platform, it.durationSec, it.categories.split(" · ").filter(String::isNotBlank).take(6)) }
                .toList()
            api.agentChat(AgentChatRequest(user, text, local))
        }.onSuccess { dialogue.add("音频助手" to it.answer) }
            .onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun saveMemory() = viewModelScope.launch {
        val text = draft.trim()
        if (text.isEmpty() || busy) return@launch
        busy = true
        error = null
        runCatching {
            val (api, user) = apiAndUser()
            val old = editing
            if (old == null) api.agentMemoryCreate(AgentMemoryRequest(user, text))
            else api.agentMemoryUpdate(user, old.id, AgentMemoryUpdate(text, old.revision))
        }.onSuccess {
            draft = ""
            editing = null
            refresh()
        }.onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun edit(memory: AgentMemoryOut) {
        editing = memory
        draft = memory.content
    }

    fun delete(memory: AgentMemoryOut) = viewModelScope.launch {
        if (busy) return@launch
        busy = true
        error = null
        runCatching {
            val (api, user) = apiAndUser()
            api.agentMemoryDelete(user, memory.id)
        }.onSuccess { refresh() }
            .onFailure { error = ApiProvider.humanMessage(it) }
        busy = false
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T = AgentViewModel(context) as T
    }
}

@Composable
fun AgentScreen() {
    val context = LocalContext.current
    val vm: AgentViewModel = viewModel(factory = AgentViewModel.Factory(context))
    LaunchedEffect(Unit) { vm.refresh() }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Text("音频助手", style = MaterialTheme.typography.titleLarge)
        Text("根据手机和服务器音频库推荐歌曲。提问、候选曲目的标题等元数据和已保存偏好会发送给服务器上的方舟模型；音频文件不会上传。聊天不会自动保存；只有明确点击保存才会记住偏好。",
            style = MaterialTheme.typography.bodySmall)
        if (!vm.serverReady) {
            Text("请先在「我的 → 服务器设置」填写服务器地址。")
        } else {
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            vm.dialogue.forEach { (speaker, message) ->
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(12.dp)) {
                        Text(speaker, style = MaterialTheme.typography.labelMedium)
                        Text(message)
                    }
                }
            }
            OutlinedTextField(value = vm.question, onValueChange = { vm.question = it },
                label = { Text("问问音频库") }, placeholder = { Text("例如：推荐一首治愈的钢琴曲") },
                modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Button(onClick = vm::ask, enabled = !vm.busy && vm.question.isNotBlank()) { Text("发送") }
            HorizontalDivider()
            Text("我的音乐偏好", style = MaterialTheme.typography.titleMedium)
            vm.memories.forEach { memory ->
                Row(Modifier.fillMaxWidth().regionFrame().padding(12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(memory.content, Modifier.weight(1f))
                    AreaButton(onClick = { vm.edit(memory) }, tone = ActionTone.CONFIRM) { Text("修改") }
                    AreaButton(onClick = { vm.delete(memory) }, enabled = !vm.busy, tone = ActionTone.DESTRUCTIVE) { Text("删除") }
                }
            }
            OutlinedTextField(value = vm.draft, onValueChange = { vm.draft = it },
                label = { Text(if (vm.editing == null) "添加音乐偏好" else "修改音乐偏好") },
                placeholder = { Text("例如：我喜欢完整的古风歌曲") },
                modifier = Modifier.fillMaxWidth(), maxLines = 3)
            Button(onClick = vm::saveMemory, enabled = !vm.busy && vm.draft.isNotBlank()) {
                Text(if (vm.editing == null) "保存偏好" else "更新偏好")
            }
        }
    }
}
