package com.getaudio.app.ui.screen

import android.content.Context
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import coil.compose.AsyncImage
import com.getaudio.app.data.remote.CredentialDto
import com.getaudio.app.data.repo.ApiProvider
import com.getaudio.app.data.repo.LoginRepository
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import retrofit2.HttpException

class LoginViewModel(private val repo: LoginRepository) : ViewModel() {

    var qrImageUrl by mutableStateOf<String?>(null)
        private set
    var state by mutableStateOf<String?>(null)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var busy by mutableStateOf(false)
        private set
    var credentials by mutableStateOf<List<CredentialDto>>(emptyList())
        private set
    /** 扫码不可用时展示 cookie 导入表单 */
    var showImport by mutableStateOf(false)
    var cookieInput by mutableStateOf("")
    var importPlatform by mutableStateOf("douyin")

    init { refresh() }

    fun refresh() = viewModelScope.launch {
        repo.credentials().onSuccess { credentials = it }
    }

    fun startQr(platform: String) = viewModelScope.launch {
        busy = true
        message = null
        qrImageUrl = null
        state = null
        showImport = false

        repo.start(platform)
            .onSuccess { resp ->
                qrImageUrl = repo.qrImageUrl(resp.qrImageUrl)
                message = "用 ${platformName(platform)} App 扫码"
                pollUntilDone(resp.sessionId, resp.expiresIn)
            }
            .onFailure { error ->
                message = ApiProvider.humanMessage(error)
                // 只有服务器明确返回 501 才提示导入 Cookie；连接失败不是平台登录限制。
                if (error is HttpException && error.code() == 501) {
                    showImport = true
                    importPlatform = platform
                }
            }
        busy = false
    }

    private suspend fun pollUntilDone(sessionId: String, expiresIn: Int) {
        val deadline = System.currentTimeMillis() + (expiresIn + 10) * 1000L
        while (System.currentTimeMillis() < deadline) {
            delay(2000)
            val resp = repo.poll(sessionId).getOrNull() ?: continue
            state = resp.state
            message = resp.message
            when (resp.state) {
                "confirmed" -> { qrImageUrl = null; refresh(); return }
                "expired", "failed" -> { qrImageUrl = null; return }
            }
        }
        message = "二维码已超时，请重新获取"
        qrImageUrl = null
    }

    fun submitCookies() = viewModelScope.launch {
        if (cookieInput.isBlank()) return@launch
        busy = true
        repo.importCookies(importPlatform, cookieInput)
            .onSuccess {
                message = "凭证已保存"
                cookieInput = ""
                showImport = false
                refresh()
            }
            .onFailure { message = ApiProvider.humanMessage(it) }
        busy = false
    }

    fun revoke(platform: String) = viewModelScope.launch {
        repo.revoke(platform).onSuccess { refresh() }
    }

    class Factory(private val context: Context) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T =
            LoginViewModel(LoginRepository(context)) as T
    }
}

private fun platformName(p: String) = if (p == "bilibili") "哔哩哔哩" else "抖音"

@Composable
fun LoginScreen() {
    val context = LocalContext.current
    val vm: LoginViewModel = viewModel(factory = LoginViewModel.Factory(context))

    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("可选服务器登录", style = MaterialTheme.typography.titleMedium)
        Text(
            "这里的凭证只供可选同步服务器使用。手机直接提取目前支持公开作品，不读取其他 App 的登录状态。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (vm.credentials.isNotEmpty()) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("已有凭证", style = MaterialTheme.typography.titleSmall)
                    vm.credentials.forEach { c ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${platformName(c.platform)} · ${if (c.status == "active") "有效" else "需重新登录"}",
                                Modifier.weight(1f),
                                style = MaterialTheme.typography.bodyMedium,
                            )
                            TextButton(onClick = { vm.revoke(c.platform) }) { Text("删除") }
                        }
                    }
                }
            }
        }

        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = { vm.startQr("bilibili") }, enabled = !vm.busy) { Text("B站扫码登录") }
            OutlinedButton(onClick = { vm.startQr("douyin") }, enabled = !vm.busy) { Text("抖音登录") }
        }

        vm.qrImageUrl?.let { url ->
            Card {
                Column(
                    Modifier.fillMaxWidth().padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    AsyncImage(model = url, contentDescription = "登录二维码", modifier = Modifier.size(240.dp))
                    Text(stateLabel(vm.state), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }

        vm.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }

        if (vm.showImport) {
            Card {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("导入浏览器 Cookie", style = MaterialTheme.typography.titleSmall)
                    Text(
                        "抖音的扫码接口被平台风控拦截。在电脑浏览器里登录抖音，" +
                            "按 F12 → Application → Cookies，复制 douyin.com 下的全部 cookie 贴到这里。",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    OutlinedTextField(
                        value = vm.cookieInput,
                        onValueChange = { vm.cookieInput = it },
                        label = { Text("sessionid=xxx; ttwid=yyy") },
                        minLines = 3,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Button(onClick = vm::submitCookies, enabled = vm.cookieInput.isNotBlank() && !vm.busy) {
                        Text("保存凭证")
                    }
                }
            }
        }
    }
}

private fun stateLabel(state: String?) = when (state) {
    "waiting", null -> "等待扫码…"
    "scanned" -> "已扫码，请在手机上确认"
    "confirmed" -> "登录成功"
    "expired" -> "二维码已失效"
    else -> "登录失败"
}
