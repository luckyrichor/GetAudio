package com.getaudio.app.data.repo

import android.content.Context
import com.getaudio.app.data.remote.CookieImportRequest
import com.getaudio.app.data.remote.CredentialDto
import com.getaudio.app.data.remote.LoginPollResponse
import com.getaudio.app.data.remote.LoginStartRequest
import com.getaudio.app.data.remote.LoginStartResponse
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class LoginRepository(context: Context) {

    private val settings = Settings(context)
    private suspend fun api() = ApiProvider.api(settings.serverUrlNow(), AccountStore.forServer(settings.serverUrlNow()) ?: error("请先登录 GetAudio 账号"))

    suspend fun start(platform: String): Result<LoginStartResponse> = runCatching {
        withContext(Dispatchers.IO) {
            api()
                .loginStart(LoginStartRequest(platform = platform))
        }
    }

    suspend fun poll(sessionId: String): Result<LoginPollResponse> = runCatching {
        withContext(Dispatchers.IO) {
            api().loginPoll(sessionId)
        }
    }

    suspend fun importCookies(platform: String, cookies: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            api().importCookies(
                CookieImportRequest(platform = platform, cookies = cookies)
            )
            Unit
        }
    }

    suspend fun credentials(): Result<List<CredentialDto>> = runCatching {
        withContext(Dispatchers.IO) {
            api().credentials()
        }
    }

    suspend fun revoke(platform: String): Result<Unit> = runCatching {
        withContext(Dispatchers.IO) {
            api().revoke(platform)
            Unit
        }
    }

    /** 二维码图片的完整地址，交给 Coil 加载。 */
    suspend fun qrImageUrl(path: String): String =
        settings.serverUrlNow().trimEnd('/') + path
}
